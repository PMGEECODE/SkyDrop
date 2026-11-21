package web

import (
	"bytes"
	"context"
	"encoding/base64"
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
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/skip2/go-qrcode"

	"skydrop/daemon/internal/client"
	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/discovery"
	"skydrop/daemon/internal/history"
	"skydrop/daemon/internal/presence"
	"skydrop/daemon/internal/security"
	"skydrop/daemon/internal/storage"
	"skydrop/daemon/internal/ui"
)

// WebController manages the web UI endpoints and browser-based sending.
type WebController struct {
	cfg             *config.Config
	pairManager     *security.PairManager
	resolver        *discovery.PeerResolver
	uploader        *client.Uploader
	notifier        *ui.Notifier
	history         *history.Manager
	storage         *storage.Storage
	presenceService *presence.Service
	fingerprint     string
	sseClientsMu    sync.Mutex
	sseClients      map[chan []byte]struct{}
	lastPairSuccess *security.PairedPeer
}

// NewWebController initializes the WebController.
func NewWebController(
	cfg *config.Config,
	pm *security.PairManager,
	resolver *discovery.PeerResolver,
	uploader *client.Uploader,
	notifier *ui.Notifier,
	histMgr *history.Manager,
) *WebController {
	wc := &WebController{
		cfg:         cfg,
		pairManager: pm,
		resolver:    resolver,
		uploader:    uploader,
		notifier:    notifier,
		history:     histMgr,
		sseClients:  make(map[chan []byte]struct{}),
	}
	if pm != nil {
		pm.OnPairSuccess(func(peer *security.PairedPeer) {
			wc.BroadcastPairSuccess(peer)
		})
	}
	return wc
}

// SetFingerprint updates the TLS certificate fingerprint.
func (wc *WebController) SetFingerprint(fp string) {
	wc.fingerprint = fp
}

// RegisterRoutes registers the web dashboard and internal API endpoints onto the mux.
func (wc *WebController) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/", wc.handleDashboard)
	mux.HandleFunc("/api/peers", wc.handleGetPeers)
	mux.HandleFunc("/api/peers/add", wc.handleAddPeer)
	mux.HandleFunc("/api/presence/events", wc.handlePresenceEvents)
	mux.HandleFunc("/api/presence/retry", wc.handlePresenceRetry)
	mux.HandleFunc("/api/diagnostics", wc.handleDiagnostics)
	mux.HandleFunc("/api/send", wc.handleSendFile)
	mux.HandleFunc("/api/pair/new", wc.handleNewPairSession)
	mux.HandleFunc("/api/pair/status", wc.handlePairStatus)
	mux.HandleFunc("/api/pair/confirm", wc.handlePairConfirmOutbound)
	mux.HandleFunc("/api/pair/unpair", wc.handleUnpairPeer)
	mux.HandleFunc("/api/settings", wc.handleSettings)
	mux.HandleFunc("/api/folder/open", wc.handleOpenFolder)
	mux.HandleFunc("/api/history", wc.handleGetHistory)
	mux.HandleFunc("/api/history/clear", wc.handleClearHistory)
	mux.HandleFunc("/api/history/delete", wc.handleDeleteHistoryEntry)
}

// PeerStatusItem provides unified discovery and pairing state for UI clients.
type PeerStatusItem struct {
	ID              string   `json:"id"`
	Name            string   `json:"name"`
	Host            string   `json:"host"`
	Port            int      `json:"port"`
	IsTLS           bool     `json:"is_tls"`
	Fingerprint     string   `json:"fingerprint,omitempty"`
	OS              string   `json:"os,omitempty"`
	IsPaired        bool     `json:"is_paired"`
	IsConnected     bool     `json:"is_connected"`
	AuthToken       string   `json:"auth_token,omitempty"`
	PresenceState   string   `json:"presence_state"`
	ErrorCode       string   `json:"error_code,omitempty"`
	ErrorMessage    string   `json:"error_message,omitempty"`
	LastSeenAt      string   `json:"last_seen_at,omitempty"`
	LatencyMs       int64    `json:"latency_ms"`
	Capabilities    []string `json:"capabilities,omitempty"`
	AppVersion      string   `json:"app_version,omitempty"`
	ProtocolVersion string   `json:"protocol_version,omitempty"`
}

func cleanDeviceName(n string) string {
	return security.NormalizeDeviceName(n)
}

func cleanDisplayName(n string) string {
	clean := strings.TrimSpace(strings.ReplaceAll(n, "\\", ""))
	if clean == "" {
		return n
	}
	return clean
}

func (wc *WebController) handleGetPeers(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("scan") == "true" || len(wc.resolver.GetCachedPeers()) == 0 {
		ctx, cancel := context.WithTimeout(r.Context(), 1200*time.Millisecond)
		defer cancel()
		_, _ = wc.resolver.DiscoverPeers(ctx, 800*time.Millisecond)
	}

	unified := wc.buildUnifiedPeerList()
	discovered := wc.resolver.GetCachedPeers()
	paired := wc.pairManager.ListPeers()

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"peers":      unified,
		"discovered": discovered,
		"paired":     paired,
		"device":     wc.cfg.DeviceName,
		"storage":    wc.cfg.StorageDir,
	})
}

func (wc *WebController) handleAddPeer(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		Host  string `json:"host"`
		Port  int    `json:"port"`
		Name  string `json:"name"`
		IsTLS *bool  `json:"is_tls"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
		return
	}

	req.Host = strings.TrimSpace(req.Host)
	if req.Host == "" {
		http.Error(w, "Host IP is required", http.StatusBadRequest)
		return
	}
	if req.Port <= 0 || req.Port > 65535 {
		req.Port = 8444
	}
	if req.Name == "" {
		req.Name = fmt.Sprintf("Manual Peer (%s)", req.Host)
	}

	isTLS := false
	if req.IsTLS != nil {
		isTLS = *req.IsTLS
	}

	peer := discovery.DiscoveredPeer{
		ID:       fmt.Sprintf("%s:%d", req.Host, req.Port),
		Name:     req.Name,
		Host:     req.Host,
		Port:     req.Port,
		IsTLS:    isTLS,
		LastSeen: time.Now().UTC(),
	}

	wc.resolver.AddCachedPeer(peer)

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "added",
		"peer":   peer,
	})
}

func (wc *WebController) handleNewPairSession(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, err := wc.pairManager.CreatePairingSession()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	hostIP := resolveHostIP(r)

	pairingURI := fmt.Sprintf("skydrop://pair?device=%s&host=%s&port=%d&secret=%s&fingerprint=%s",
		url.QueryEscape(wc.cfg.DeviceName), hostIP, wc.cfg.Port, session.Secret, url.QueryEscape(wc.fingerprint))

	var qrBase64 string
	if qrPNG, err := qrcode.Encode(pairingURI, qrcode.Medium, 256); err == nil {
		qrBase64 = "data:image/png;base64," + base64.StdEncoding.EncodeToString(qrPNG)
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"secret":      session.Secret,
		"expires_at":  session.ExpiresAt.Format(time.RFC3339),
		"pairing_uri": pairingURI,
		"qr_code":     qrBase64,
	})
}

func (wc *WebController) handlePairStatus(w http.ResponseWriter, r *http.Request) {
	wc.sseClientsMu.Lock()
	last := wc.lastPairSuccess
	wc.sseClientsMu.Unlock()

	res := map[string]interface{}{
		"has_active_session": wc.pairManager != nil && wc.pairManager.HasActiveSession(),
	}
	if last != nil {
		res["last_paired"] = map[string]interface{}{
			"device_name": last.DeviceName,
			"peer_id":     last.ID,
			"paired_at":   last.PairedAt.Format(time.RFC3339),
		}
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(res)
}

func (wc *WebController) handlePairConfirmOutbound(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		Host       string `json:"host"`
		Port       int    `json:"port"`
		IsTLS      bool   `json:"is_tls"`
		Secret     string `json:"secret"`
		DeviceName string `json:"device_name"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Host == "" || req.Secret == "" {
		http.Error(w, "host and secret are required", http.StatusBadRequest)
		return
	}

	if req.Port <= 0 {
		req.Port = 8443
	}

	scheme := "http"
	if req.IsTLS {
		scheme = "https"
	}

	confirmPayload := map[string]interface{}{
		"device_name": wc.cfg.DeviceName,
		"fingerprint": "",
		"secret":      req.Secret,
		"host":        resolveHostIP(r),
		"port":        wc.cfg.Port,
		"is_tls":      wc.cfg.EnableTLS,
		"device_type": "desktop",
		"device_id":   wc.cfg.DeviceName,
	}

	bodyBytes, _ := json.Marshal(confirmPayload)
	confirmURL := fmt.Sprintf("%s://%s:%d/pair/confirm", scheme, req.Host, req.Port)

	client := wc.uploader.HTTPClient()
	httpReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost, confirmURL, bytes.NewReader(bodyBytes))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-SkyDrop-Device-ID", wc.cfg.DeviceName)

	resp, err := client.Do(httpReq)
	if err != nil && req.IsTLS {
		// Fallback to HTTP
		scheme = "http"
		confirmURL = fmt.Sprintf("http://%s:%d/pair/confirm", req.Host, req.Port)
		if fbReq, fbErr := http.NewRequestWithContext(r.Context(), http.MethodPost, confirmURL, bytes.NewReader(bodyBytes)); fbErr == nil {
			fbReq.Header.Set("Content-Type", "application/json")
			fbReq.Header.Set("X-SkyDrop-Device-ID", wc.cfg.DeviceName)
			resp, err = client.Do(fbReq)
		}
	}

	if err != nil {
		http.Error(w, fmt.Sprintf("Failed to confirm pairing with %s: %v", req.Host, err), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		b, _ := io.ReadAll(resp.Body)
		http.Error(w, fmt.Sprintf("Remote pairing failed: %s", string(b)), resp.StatusCode)
		return
	}

	var resData struct {
		Status      string `json:"status"`
		AuthToken   string `json:"auth_token"`
		DeviceID    string `json:"device_id"`
		DeviceName  string `json:"device_name"`
		Fingerprint string `json:"fingerprint"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&resData); err != nil || resData.AuthToken == "" {
		http.Error(w, "Invalid response from peer", http.StatusBadGateway)
		return
	}

	pairedPeer := &security.PairedPeer{
		ID:          resData.DeviceID,
		DeviceName:  resData.DeviceName,
		Host:        req.Host,
		Port:        req.Port,
		IsTLS:       scheme == "https",
		Fingerprint: resData.Fingerprint,
		AuthToken:   resData.AuthToken,
		PairedAt:    time.Now().UTC(),
		LastSeenAt:  time.Now().UTC(),
	}
	if pairedPeer.DeviceName == "" {
		pairedPeer.DeviceName = req.DeviceName
	}
	if pairedPeer.ID == "" {
		pairedPeer.ID = fmt.Sprintf("%s:%d", req.Host, req.Port)
	}

	if err := wc.pairManager.AddPairedPeer(pairedPeer); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if wc.presenceService != nil {
		wc.presenceService.TriggerImmediateCheck()
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":      "paired",
		"device_id":   pairedPeer.ID,
		"device_name": pairedPeer.DeviceName,
		"host":        pairedPeer.Host,
		"port":        pairedPeer.Port,
		"is_tls":      pairedPeer.IsTLS,
	})
}

// resolveHostIP extracts or discovers the best routable IP address for client pairing.
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

func (wc *WebController) handleSendFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	mediaType, params, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if !strings.HasPrefix(mediaType, "multipart/") {
		http.Error(w, "Expected multipart/form-data", http.StatusBadRequest)
		return
	}

	mr := multipart.NewReader(r.Body, params["boundary"])
	var targetURL string
	var authToken string
	var tempFilePath string
	var originalFilename string

	for {
		part, err := mr.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			http.Error(w, "Error reading multipart", http.StatusBadRequest)
			return
		}

		formName := part.FormName()
		if formName == "target_url" {
			val, _ := io.ReadAll(part)
			targetURL = strings.TrimSpace(string(val))
		} else if formName == "auth_token" {
			val, _ := io.ReadAll(part)
			authToken = strings.TrimSpace(string(val))
		} else if formName == "file" {
			originalFilename = part.FileName()
			tempFile, err := os.CreateTemp("", "skydrop-web-upload-*")
			if err != nil {
				part.Close()
				http.Error(w, "Failed to create temp file", http.StatusInternalServerError)
				return
			}
			tempFilePath = tempFile.Name()
			_, _ = io.Copy(tempFile, part)
			tempFile.Close()
		}
		part.Close()
	}

	if targetURL == "" || tempFilePath == "" {
		if tempFilePath != "" {
			_ = os.Remove(tempFilePath)
		}
		http.Error(w, "Missing target_url or file payload", http.StatusBadRequest)
		return
	}

	if u, err := url.Parse(targetURL); err == nil {
		host := u.Hostname()
		if host == "" {
			host = u.Host
		}
		portStr := u.Port()
		port := 0
		if portStr != "" {
			port, _ = strconv.Atoi(portStr)
		}
		if (port == wc.cfg.Port || port == 0) && discovery.IsLocalAddress(host) {
			_ = os.Remove(tempFilePath)
			http.Error(w, "Cannot send files to local device", http.StatusBadRequest)
			return
		}
	}

	defer os.Remove(tempFilePath)

	// If no auth token explicitly passed, lookup token from paired store by matching target host
	if authToken == "" {
		peersList := wc.pairManager.ListPeers()
		var targetHost string
		if u, err := url.Parse(targetURL); err == nil {
			targetHost = u.Hostname()
		}
		for _, p := range peersList {
			if (targetHost != "" && p.Host == targetHost) || (targetHost != "" && strings.EqualFold(p.DeviceName, targetHost)) {
				authToken = p.AuthToken
				break
			}
		}
		if authToken == "" && len(peersList) > 0 {
			authToken = peersList[0].AuthToken
		}
	}

	uploadCtx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()

	fileStat, _ := os.Stat(tempFilePath)
	var fileSize int64
	if fileStat != nil {
		fileSize = fileStat.Size()
	}

	err := wc.uploader.UploadFileWithFilename(uploadCtx, targetURL, tempFilePath, originalFilename, authToken, nil)
	if err != nil {
		if wc.history != nil {
			_ = wc.history.AddEntry(history.Entry{
				Direction:    history.DirectionOutbound,
				ItemType:     "file",
				Filename:     originalFilename,
				Size:         fileSize,
				PeerURL:      targetURL,
				Status:       history.StatusFailed,
				ErrorMessage: err.Error(),
			})
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		var upErr *client.UploadError
		if errors.As(err, &upErr) {
			statusCode := http.StatusBadGateway
			if upErr.StatusCode >= 400 && upErr.StatusCode < 600 {
				statusCode = upErr.StatusCode
			}
			w.WriteHeader(statusCode)
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"error":       "upload_failed",
				"category":    upErr.Category,
				"message":     upErr.Message,
				"details":     upErr.Details,
				"status_code": upErr.StatusCode,
				"target_url":  upErr.TargetURL,
			})
			return
		}

		w.WriteHeader(http.StatusBadGateway)
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"error":       "upload_failed",
			"category":    "unknown",
			"message":     err.Error(),
			"details":     err.Error(),
			"status_code": http.StatusBadGateway,
			"target_url":  targetURL,
		})
		return
	}

	if wc.history != nil {
		_ = wc.history.AddEntry(history.Entry{
			Direction: history.DirectionOutbound,
			ItemType:  "file",
			Filename:  originalFilename,
			Size:      fileSize,
			PeerURL:   targetURL,
			Status:    history.StatusCompleted,
		})
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]string{
		"status":   "success",
		"filename": originalFilename,
	})
}

func (wc *WebController) handleOpenFolder(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	dir := wc.cfg.StorageDir
	_ = os.MkdirAll(dir, 0755)
	absPath, _ := filepath.Abs(dir)
	_ = wc.notifier.OpenPath(absPath)

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "opened", "path": absPath})
}

func (wc *WebController) handleGetHistory(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	if wc.history == nil {
		_ = json.NewEncoder(w).Encode([]history.Entry{})
		return
	}
	entries := wc.history.ListEntries(100)
	_ = json.NewEncoder(w).Encode(entries)
}

func (wc *WebController) handleClearHistory(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if wc.history != nil {
		_ = wc.history.Clear()
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "cleared"})
}

func (wc *WebController) handleDeleteHistoryEntry(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		http.Error(w, "Invalid entry ID", http.StatusBadRequest)
		return
	}
	if wc.history != nil {
		_ = wc.history.DeleteEntry(req.ID)
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "deleted", "id": req.ID})
}
