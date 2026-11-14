package server

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/skip2/go-qrcode"

	"skydrop/daemon/internal/history"
	"skydrop/daemon/internal/presence"
	"skydrop/daemon/internal/storage"
)

const (
	MaxTextLength = 1024 * 1024 // 1 MB text limit
)

// InfoResponse provides public metadata about this daemon instance.
type InfoResponse struct {
	DeviceName  string `json:"device_name"`
	Version     string `json:"version"`
	OS          string `json:"os"`
	Protocol    string `json:"protocol"`
	TLSEnabled  bool   `json:"tls_enabled"`
	Fingerprint string `json:"fingerprint,omitempty"`
}

// UploadResponse details the outcome of an uploaded file.
type UploadResponse struct {
	Status        string `json:"status"`
	OriginalName  string `json:"original_name"`
	SanitizedName string `json:"sanitized_name"`
	SavedPath     string `json:"saved_path"`
	BytesWritten  int64  `json:"bytes_written"`
	ReceivedAt    string `json:"received_at"`
}

// TextRequest contains text or link payload shared from peer.
type TextRequest struct {
	Text string `json:"text"`
}

// TextResponse confirms receipt of shared text.
type TextResponse struct {
	Status        string `json:"status"`
	BytesReceived int    `json:"bytes_received"`
}

// ErrorResponse standardizes error responses.
type ErrorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// writeJSON writes a standardized JSON response.
func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

// writeError writes a standardized JSON error response.
func writeError(w http.ResponseWriter, status int, errType, message string) {
	writeJSON(w, status, ErrorResponse{
		Error:   errType,
		Message: message,
	})
}

// handleInfo returns system metadata and version info.
func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET is supported on /info")
		return
	}

	writeJSON(w, http.StatusOK, InfoResponse{
		DeviceName:  s.cfg.DeviceName,
		Version:     presence.AppVersion,
		OS:          "linux",
		Protocol:    "skydrop/v1",
		TLSEnabled:  s.cfg.EnableTLS,
		Fingerprint: s.fingerprint,
	})
}

// handleHealth returns live system health, capabilities, and device info.
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET is supported on /health")
		return
	}

	state := "idle"
	if s.isTransferring != nil && s.isTransferring() {
		state = "transferring"
	}

	writeJSON(w, http.StatusOK, presence.HealthResponse{
		DeviceID:        s.cfg.DeviceName,
		DeviceName:      s.cfg.DeviceName,
		AppVersion:      presence.AppVersion,
		ProtocolVersion: presence.ProtocolVersion,
		Capabilities:    presence.DefaultCapabilities,
		State:           state,
		Fingerprint:     s.fingerprint,
	})
}

// handlePresenceLeave processes graceful departure notification from a peer.
func (s *Server) handlePresenceLeave(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /presence/leave")
		return
	}

	var req presence.LeaveRequest
	_ = json.NewDecoder(r.Body).Decode(&req)
	peerID := r.Header.Get("X-SkyDrop-Device-ID")
	if req.DeviceID != "" {
		peerID = req.DeviceID
	}
	if s.presenceService != nil && peerID != "" {
		s.presenceService.HandlePeerLeave(peerID, req.Reason)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "acknowledged"})
}

// handleUpload accepts either a raw stream or multipart form upload, and supports resumable chunks.
func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodHead {
		filename := r.Header.Get("X-Filename")
		if filename == "" {
			filename = r.URL.Query().Get("filename")
		}
		if filename == "" {
			writeError(w, http.StatusBadRequest, "missing_filename", "X-Filename header or filename query param required")
			return
		}
		offset, err := s.storage.GetPartialOffset(filename)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_filename", err.Error())
			return
		}
		w.Header().Set("Upload-Offset", strconv.FormatInt(offset, 10))
		w.WriteHeader(http.StatusOK)
		return
	}

	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST and HEAD are supported on /upload")
		return
	}

	contentType := r.Header.Get("Content-Type")
	mediaType, params, _ := mime.ParseMediaType(contentType)

	var reader io.Reader
	var filename string
	declaredSize := r.ContentLength

	// Check for resumable offset
	var offset int64
	if offsetHdr := r.Header.Get("Upload-Offset"); offsetHdr != "" {
		if parsedOffset, err := strconv.ParseInt(offsetHdr, 10, 64); err == nil && parsedOffset >= 0 {
			offset = parsedOffset
		}
	} else if offsetHdr := r.Header.Get("X-Upload-Offset"); offsetHdr != "" {
		if parsedOffset, err := strconv.ParseInt(offsetHdr, 10, 64); err == nil && parsedOffset >= 0 {
			offset = parsedOffset
		}
	}

	// Check total expected size for resumable transfers
	if totalHdr := r.Header.Get("X-Total-Size"); totalHdr != "" {
		if parsedTotal, err := strconv.ParseInt(totalHdr, 10, 64); err == nil && parsedTotal > 0 {
			declaredSize = parsedTotal
		}
	} else if offset > 0 && declaredSize > 0 {
		declaredSize += offset
	}

	var isMultipart bool
	if strings.HasPrefix(mediaType, "multipart/") {
		isMultipart = true
		mr := multipart.NewReader(r.Body, params["boundary"])
		part, err := mr.NextPart()
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_multipart", "Failed to parse multipart body")
			return
		}
		defer part.Close()

		filename = part.FileName()
		if filename == "" {
			filename = part.FormName()
		}
		reader = part
	} else {
		// Raw stream body
		filename = r.Header.Get("X-Filename")
		if filename == "" {
			// Check Content-Disposition if present
			if cd := r.Header.Get("Content-Disposition"); cd != "" {
				if _, cdParams, err := mime.ParseMediaType(cd); err == nil {
					filename = cdParams["filename"]
				}
			}
		}

		if filename == "" {
			writeError(w, http.StatusBadRequest, "missing_filename", "X-Filename header or Content-Disposition filename is required for raw upload")
			return
		}
		reader = r.Body
	}

	var info *storage.SavedFileInfo
	var isComplete bool
	var err error

	progressCb := func(bytesWritten, totalBytes int64, percent int) {
		if s.webCtrl != nil {
			s.webCtrl.BroadcastReceiveProgress(filename, bytesWritten, totalBytes, percent, "receiving")
		}
	}

	if isMultipart {
		info, err = s.storage.SaveStream(filename, reader, s.cfg.MaxFileSize)
		isComplete = true
	} else {
		info, isComplete, err = s.storage.SaveStreamResumableWithProgress(filename, reader, declaredSize, offset, progressCb)
	}
	if err != nil {
		if s.webCtrl != nil {
			s.webCtrl.BroadcastReceiveProgress(filename, 0, declaredSize, 0, "failed")
		}
		if errors.Is(err, storage.ErrFileTooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "file_too_large", err.Error())
			return
		}
		if errors.Is(err, storage.ErrOffsetMismatch) {
			writeError(w, http.StatusConflict, "offset_mismatch", err.Error())
			return
		}
		writeError(w, http.StatusBadRequest, "upload_failed", err.Error())
		return
	}

	if isComplete {
		if s.webCtrl != nil {
			s.webCtrl.BroadcastReceiveProgress(info.SanitizedName, info.TotalBytes, info.TotalBytes, 100, "completed")
		}
		// Checksum verification if header provided
		expectedSHA := strings.TrimSpace(r.Header.Get("X-Checksum-SHA256"))
		if expectedSHA != "" {
			f, openErr := os.Open(info.SavedPath)
			if openErr == nil {
				hasher := sha256.New()
				if _, copyErr := io.Copy(hasher, f); copyErr == nil {
					actualSHA := hex.EncodeToString(hasher.Sum(nil))
					if !strings.EqualFold(actualSHA, expectedSHA) {
						f.Close()
						_ = os.Remove(info.SavedPath)
						writeError(w, http.StatusUnprocessableEntity, "checksum_mismatch", fmt.Sprintf("SHA-256 mismatch: expected %s, got %s", expectedSHA, actualSHA))
						return
					}
				}
				f.Close()
			}
		}

		if peerID := r.Header.Get("X-SkyDrop-Device-ID"); peerID != "" && s.presenceService != nil {
			s.presenceService.RecordActivity(peerID)
		}

		if s.notifier != nil {
			_ = s.notifier.NotifyFileReceived(info.SanitizedName, info.SavedPath, "")
		}
		if s.history != nil {
			_ = s.history.AddEntry(history.Entry{
				Direction: history.DirectionInbound,
				ItemType:  "file",
				Filename:  info.SanitizedName,
				Size:      info.TotalBytes,
				SavedPath: info.SavedPath,
				Status:    history.StatusCompleted,
			})
		}

		writeJSON(w, http.StatusOK, UploadResponse{
			Status:        "success",
			OriginalName:  info.OriginalName,
			SanitizedName: info.SanitizedName,
			SavedPath:     info.SavedPath,
			BytesWritten:  info.BytesWritten,
			ReceivedAt:    info.ReceivedAt.Format("2006-01-02T15:04:05Z07:00"),
		})
		return
	}

	w.Header().Set("Upload-Offset", strconv.FormatInt(info.TotalBytes, 10))
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":   "partial",
		"offset":   info.TotalBytes,
		"filename": info.SanitizedName,
	})
}

// handleText receives text messages or shared URLs.
func (s *Server) handleText(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /text")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, MaxTextLength)
	var req TextRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "Failed to parse JSON body or payload exceeded limit")
		return
	}

	trimmed := strings.TrimSpace(req.Text)
	if trimmed == "" {
		writeError(w, http.StatusBadRequest, "empty_text", "Text payload cannot be empty")
		return
	}

	if !utf8.ValidString(trimmed) {
		writeError(w, http.StatusBadRequest, "invalid_utf8", "Text payload must be valid UTF-8")
		return
	}

	if s.notifier != nil {
		_ = s.notifier.NotifyTextReceived(trimmed, "")
	}
	if s.history != nil {
		_ = s.history.AddEntry(history.Entry{
			Direction: history.DirectionInbound,
			ItemType:  "text",
			Filename:  trimmed,
			Size:      int64(len(trimmed)),
			Status:    history.StatusCompleted,
		})
	}

	writeJSON(w, http.StatusOK, TextResponse{
		Status:        "success",
		BytesReceived: len(trimmed),
	})
}

// PairStartResponse returns ephemeral pairing credentials and URI.
type PairStartResponse struct {
	Status      string `json:"status"`
	DeviceName  string `json:"device_name"`
	Host        string `json:"host"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Port        int    `json:"port"`
	Secret      string `json:"secret"`
	PairingURI  string `json:"pairing_uri"`
	QRCode      string `json:"qr_code,omitempty"`
	ExpiresAt   string `json:"expires_at"`
}

// PairConfirmRequest is submitted by the scanning phone.
type PairConfirmRequest struct {
	DeviceName  string `json:"device_name"`
	Fingerprint string `json:"fingerprint"`
	Secret      string `json:"secret"`
}

// PairConfirmResponse completes mutual pairing.
type PairConfirmResponse struct {
	Status      string `json:"status"`
	AuthToken   string `json:"auth_token"`
	DeviceID    string `json:"device_id"`
	DeviceName  string `json:"device_name"`
	Fingerprint string `json:"fingerprint,omitempty"`
}

// requireAuth validates that incoming requests carry a valid Bearer token if pairing is required.
func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.cfg.RequirePairing {
			next(w, r)
			return
		}

		// Allow loopback calls from local desktop UI without bearer token
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err == nil && (host == "127.0.0.1" || host == "::1" || host == "localhost") {
			next(w, r)
			return
		}

		authHeader := r.Header.Get("Authorization")
		if !strings.HasPrefix(authHeader, "Bearer ") {
			writeError(w, http.StatusUnauthorized, "unauthorized", "Missing or invalid Authorization Bearer header")
			return
		}

		token := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
		if token == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "Empty bearer token")
			return
		}

		if _, err := s.pairManager.ValidateToken(token); err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized", "Device not paired or token invalid")
			return
		}

		next(w, r)
	}
}

// handlePairStart creates a new ephemeral pairing session.
func (s *Server) handlePairStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /pair/start")
		return
	}

	session, err := s.pairManager.CreatePairingSession()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "pairing_error", err.Error())
		return
	}

	hostIP := resolveHostIP(r)

	pairingURI := fmt.Sprintf("skydrop://pair?device=%s&host=%s&port=%d&secret=%s&fingerprint=%s",
		url.QueryEscape(s.cfg.DeviceName), hostIP, s.cfg.Port, session.Secret, url.QueryEscape(s.fingerprint))

	var qrBase64 string
	if qrPNG, err := qrcode.Encode(pairingURI, qrcode.Medium, 256); err == nil {
		qrBase64 = "data:image/png;base64," + base64.StdEncoding.EncodeToString(qrPNG)
	}

	writeJSON(w, http.StatusOK, PairStartResponse{
		Status:      "ready",
		DeviceName:  s.cfg.DeviceName,
		Host:        hostIP,
		Fingerprint: s.fingerprint,
		Port:        s.cfg.Port,
		Secret:      session.Secret,
		PairingURI:  pairingURI,
		QRCode:      qrBase64,
		ExpiresAt:   session.ExpiresAt.Format("2006-01-02T15:04:05Z07:00"),
	})
}

// handlePairConfirm handles the peer's confirmation with one-time secret.
func (s *Server) handlePairConfirm(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /pair/confirm")
		return
	}

	var req PairConfirmRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "Failed to parse pair confirmation JSON")
		return
	}

	if (req.Fingerprint != "" && req.Fingerprint == s.fingerprint) || (req.DeviceName != "" && req.DeviceName == s.cfg.DeviceName) {
		writeError(w, http.StatusBadRequest, "cannot_pair_self", "A device cannot pair with itself")
		return
	}

	peer, err := s.pairManager.ConfirmPairing(req.DeviceName, req.Fingerprint, req.Secret)
	if err != nil {
		writeError(w, http.StatusForbidden, "pairing_failed", err.Error())
		return
	}

	writeJSON(w, http.StatusOK, PairConfirmResponse{
		Status:      "paired",
		AuthToken:   peer.AuthToken,
		DeviceID:    peer.ID,
		DeviceName:  s.cfg.DeviceName,
		Fingerprint: s.fingerprint,
	})
}

// handleListPeers lists already-paired devices.
func (s *Server) handleListPeers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET is supported on /pair/peers")
		return
	}

	peers := s.pairManager.ListPeers()
	writeJSON(w, http.StatusOK, peers)
}

func resolveHostIP(r *http.Request) string {
	if r != nil {
		host, _, err := net.SplitHostPort(r.Host)
		if err == nil && host != "localhost" && host != "127.0.0.1" && host != "::1" && host != "" {
			return host
		}
	}
	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, addr := range addrs {
				if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() {
					if v4 := ipNet.IP.To4(); v4 != nil {
						return v4.String()
					}
				}
			}
		}
	}
	return "127.0.0.1"
}
