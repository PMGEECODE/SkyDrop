package client

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// UploadError represents a rich, structured error encountered during transfer.
type UploadError struct {
	StatusCode int    `json:"status_code"`
	Category   string `json:"category"`
	Message    string `json:"message"`
	Details    string `json:"details,omitempty"`
	TargetURL  string `json:"target_url"`
}

func (e *UploadError) Error() string {
	if e.Details != "" {
		return fmt.Sprintf("%s (%s)", e.Message, e.Details)
	}
	return e.Message
}

func maskAuthToken(tok string) string {
	if len(tok) <= 8 {
		return "***"
	}
	return tok[:4] + "..." + tok[len(tok)-4:]
}


// Uploader streams files and text to remote peers.
type Uploader struct {
	httpClient *http.Client
}

// UploadProgressCallback reports progress during streaming uploads.
type UploadProgressCallback func(bytesWritten, totalBytes int64, percent int)

// BatchProgressCallback reports overall batch upload progress.
type BatchProgressCallback func(fileIndex, totalFiles int, filename string, written, total int64, percent int)

// ProgressReader wraps an io.Reader to track upload progress.
type ProgressReader struct {
	reader     io.Reader
	totalBytes int64
	written    int64
	onProgress UploadProgressCallback
}

func (pr *ProgressReader) Read(p []byte) (int, error) {
	n, err := pr.reader.Read(p)
	if n > 0 {
		pr.written += int64(n)
		if pr.onProgress != nil {
			percent := 0
			if pr.totalBytes > 0 {
				percent = int((pr.written * 100) / pr.totalBytes)
			}
			pr.onProgress(pr.written, pr.totalBytes, percent)
		}
	}
	return n, err
}

// NewUploader creates an Uploader with configured timeout and TLS settings.
func NewUploader(insecureTLS bool) *Uploader {
	transport := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: insecureTLS,
		},
		ResponseHeaderTimeout: 30 * time.Second,
		IdleConnTimeout:       90 * time.Second,
	}

	return &Uploader{
		httpClient: &http.Client{
			Transport: transport,
			Timeout:   0, // no timeout on body stream to allow large files
		},
	}
}

// getServerOffset queries the remote peer for an existing partial file offset.
func (u *Uploader) getServerOffset(ctx context.Context, peerURL, filename, authToken string) (int64, error) {
	endpoint := fmt.Sprintf("%s/upload?filename=%s", peerURL, filename)
	headReq, err := http.NewRequestWithContext(ctx, http.MethodHead, endpoint, nil)
	if err != nil {
		return 0, err
	}
	headReq.Header.Set("X-Filename", filename)
	if authToken != "" {
		headReq.Header.Set("Authorization", "Bearer "+authToken)
	}

	resp, err := u.httpClient.Do(headReq)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK {
		offsetStr := resp.Header.Get("Upload-Offset")
		if offsetStr != "" {
			if offset, err := strconv.ParseInt(offsetStr, 10, 64); err == nil && offset >= 0 {
				return offset, nil
			}
		}
	}
	return 0, nil
}

// UploadFile streams a local file to the target peer with real-time progress callbacks and auto-resume.
func (u *Uploader) UploadFile(
	ctx context.Context,
	peerURL string,
	filePath string,
	authToken string,
	onProgress UploadProgressCallback,
) error {
	return u.UploadFileWithFilename(ctx, peerURL, filePath, "", authToken, onProgress)
}

// UploadFileWithFilename streams a local file using a custom override filename (e.g. for web multipart uploads).
func (u *Uploader) UploadFileWithFilename(
	ctx context.Context,
	peerURL string,
	filePath string,
	customFilename string,
	authToken string,
	onProgress UploadProgressCallback,
) error {
	file, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("failed to open file %s: %w", filePath, err)
	}
	defer file.Close()

	stat, err := file.Stat()
	if err != nil {
		return fmt.Errorf("failed to stat file: %w", err)
	}

	if stat.IsDir() {
		return errors.New("cannot upload a directory directly; must be a file")
	}

	filename := filepath.Base(filePath)
	if customFilename != "" {
		filename = filepath.Base(customFilename)
	}
	totalSize := stat.Size()

	// Query server for resumable offset
	var offset int64
	serverOffset, err := u.getServerOffset(ctx, peerURL, filename, authToken)
	if err == nil && serverOffset > 0 && serverOffset < totalSize {
		if _, seekErr := file.Seek(serverOffset, io.SeekStart); seekErr == nil {
			offset = serverOffset
		}
	}

	pr := &ProgressReader{
		reader:     file,
		totalBytes: totalSize,
		written:    offset,
		onProgress: onProgress,
	}

	uploadEndpoint := fmt.Sprintf("%s/upload", peerURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, uploadEndpoint, pr)
	if err != nil {
		return fmt.Errorf("failed to create upload request: %w", err)
	}

	req.ContentLength = totalSize - offset
	req.Header.Set("X-Filename", filename)
	req.Header.Set("Content-Type", "application/octet-stream")
	if offset > 0 {
		req.Header.Set("Upload-Offset", strconv.FormatInt(offset, 10))
		req.Header.Set("X-Total-Size", strconv.FormatInt(totalSize, 10))
	}
	if authToken != "" {
		req.Header.Set("Authorization", "Bearer "+authToken)
	}

	log.Printf("[Uploader] Dispatching %s -> %s (Size: %d bytes, Auth: %s)", filename, uploadEndpoint, totalSize, maskAuthToken(authToken))

	resp, err := u.httpClient.Do(req)
	if err != nil {
		netErr := classifyNetworkError(uploadEndpoint, err)
		log.Printf("[Uploader ERR] %s -> %s network failure: %v", filename, uploadEndpoint, netErr)
		return netErr
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(resp.Body)
		httpErr := classifyHTTPError(uploadEndpoint, resp.StatusCode, body)
		log.Printf("[Uploader ERR] %s -> %s rejected: HTTP %d: %s", filename, uploadEndpoint, resp.StatusCode, string(body))
		return httpErr
	}

	log.Printf("[Uploader OK] %s successfully transferred to %s (HTTP %d)", filename, uploadEndpoint, resp.StatusCode)
	return nil
}

func classifyNetworkError(targetURL string, err error) *UploadError {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return &UploadError{
			StatusCode: 0,
			Category:   "TIMEOUT",
			Message:    "Connection timed out waiting for phone",
			Details:    fmt.Sprintf("Timeout contacting %s: ensure the phone screen is on and connected to the same Wi-Fi", targetURL),
			TargetURL:  targetURL,
		}
	}
	msg := err.Error()
	if strings.Contains(msg, "connection refused") {
		return &UploadError{
			StatusCode: 0,
			Category:   "CONNECTION_REFUSED",
			Message:    "Phone not reachable (Connection refused)",
			Details:    fmt.Sprintf("Failed to connect to %s: verify phone receiver mode is turned on and Wi-Fi IP matches", targetURL),
			TargetURL:  targetURL,
		}
	}
	if strings.Contains(msg, "timeout") || strings.Contains(msg, "deadline exceeded") {
		return &UploadError{
			StatusCode: 0,
			Category:   "TIMEOUT",
			Message:    "Connection timed out waiting for phone",
			Details:    fmt.Sprintf("Timeout contacting %s: ensure the phone screen is on and connected to the same Wi-Fi", targetURL),
			TargetURL:  targetURL,
		}
	}
	if strings.Contains(msg, "certificate") || strings.Contains(msg, "tls") {
		return &UploadError{
			StatusCode: 0,
			Category:   "TLS_ERROR",
			Message:    "Security handshake / TLS error",
			Details:    fmt.Sprintf("TLS verification failed with %s: %s", targetURL, msg),
			TargetURL:  targetURL,
		}
	}
	return &UploadError{
		StatusCode: 0,
		Category:   "NETWORK_ERROR",
		Message:    "Network error communicating with phone",
		Details:    fmt.Sprintf("%s (%s)", msg, targetURL),
		TargetURL:  targetURL,
	}
}

func classifyHTTPError(targetURL string, statusCode int, body []byte) *UploadError {
	raw := strings.TrimSpace(string(body))
	var serverMsg string
	var parsed map[string]interface{}
	if err := json.Unmarshal(body, &parsed); err == nil {
		if m, ok := parsed["message"].(string); ok && m != "" {
			serverMsg = m
		} else if e, ok := parsed["error"].(string); ok && e != "" {
			serverMsg = e
		}
	}
	if serverMsg == "" {
		serverMsg = raw
	}

	switch statusCode {
	case http.StatusUnauthorized, http.StatusForbidden:
		return &UploadError{
			StatusCode: statusCode,
			Category:   "UNAUTHORIZED",
			Message:    "Not paired: Phone rejected authorization token",
			Details:    fmt.Sprintf("HTTP %d from %s: %s (Try pairing the device again)", statusCode, targetURL, serverMsg),
			TargetURL:  targetURL,
		}
	case http.StatusNotFound:
		return &UploadError{
			StatusCode: statusCode,
			Category:   "NOT_FOUND",
			Message:    "Receiver endpoint not found on phone",
			Details:    fmt.Sprintf("HTTP 404 from %s: Ensure SkyDrop app on the phone is active", targetURL),
			TargetURL:  targetURL,
		}
	case http.StatusRequestEntityTooLarge:
		return &UploadError{
			StatusCode: statusCode,
			Category:   "FILE_TOO_LARGE",
			Message:    "Phone refused the file: File is too large",
			Details:    fmt.Sprintf("HTTP 413 from %s: %s", targetURL, serverMsg),
			TargetURL:  targetURL,
		}
	default:
		return &UploadError{
			StatusCode: statusCode,
			Category:   "RECEIVER_ERROR",
			Message:    fmt.Sprintf("Phone returned error (HTTP %d)", statusCode),
			Details:    fmt.Sprintf("HTTP %d from %s: %s", statusCode, targetURL, serverMsg),
			TargetURL:  targetURL,
		}
	}
}

// UploadFiles streams multiple files sequentially, reporting batch progress.
func (u *Uploader) UploadFiles(
	ctx context.Context,
	peerURL string,
	filePaths []string,
	authToken string,
	onBatchProgress BatchProgressCallback,
) ([]string, error) {
	if len(filePaths) == 0 {
		return nil, errors.New("no files specified for upload")
	}

	uploaded := make([]string, 0, len(filePaths))
	totalFiles := len(filePaths)

	for i, path := range filePaths {
		filename := filepath.Base(path)
		err := u.UploadFile(ctx, peerURL, path, authToken, func(written, total int64, percent int) {
			if onBatchProgress != nil {
				onBatchProgress(i+1, totalFiles, filename, written, total, percent)
			}
		})
		if err != nil {
			return uploaded, fmt.Errorf("failed uploading %s (%d/%d): %w", filename, i+1, totalFiles, err)
		}
		uploaded = append(uploaded, filename)
	}

	return uploaded, nil
}

// SendText shares a text message or URL to the target peer.
func (u *Uploader) SendText(ctx context.Context, peerURL, text, authToken string) error {
	payload, err := json.Marshal(map[string]string{"text": text})
	if err != nil {
		return fmt.Errorf("failed to serialize text payload: %w", err)
	}

	textEndpoint := fmt.Sprintf("%s/text", peerURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, textEndpoint, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("failed to create text request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	if authToken != "" {
		req.Header.Set("Authorization", "Bearer "+authToken)
	}

	resp, err := u.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("text request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("send text failed with HTTP %d: %s", resp.StatusCode, string(body))
	}

	return nil
}

// PostClipboardItem transmits a clipboard payload to a peer's /clipboard/add endpoint.
func (u *Uploader) PostClipboardItem(ctx context.Context, peerURL string, item interface{}, authToken string) error {
	payload, err := json.Marshal(item)
	if err != nil {
		return fmt.Errorf("failed to serialize clipboard item: %w", err)
	}

	endpoint := fmt.Sprintf("%s/clipboard/add", peerURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("failed to create clipboard request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	if authToken != "" {
		req.Header.Set("Authorization", "Bearer "+authToken)
	}

	resp, err := u.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("clipboard post failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("clipboard post failed with HTTP %d: %s", resp.StatusCode, string(body))
	}

	return nil
}

// HTTPClient returns the configured http.Client instance.
func (u *Uploader) HTTPClient() *http.Client {
	return u.httpClient
}
