package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/security"
	"skydrop/daemon/internal/storage"
)

func setupTestServer(t *testing.T, maxFileSize int64) (*Server, string) {
	t.Helper()
	tmpDir, err := os.MkdirTemp("", "skydrop-srv-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	store, err := storage.NewStorage(tmpDir, maxFileSize)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	pm, err := security.NewPairManager(filepath.Join(tmpDir, "paired.json"))
	if err != nil {
		t.Fatalf("failed to create pair manager: %v", err)
	}

	cfg := &config.Config{
		Port:           8443,
		DeviceName:     "TestDevice",
		ServiceName:    "_filedrop._tcp",
		StorageDir:     tmpDir,
		MaxFileSize:    maxFileSize,
		EnableTLS:      false, // keep plain for fast unit testing of handlers
		RequirePairing: false,
	}

	srv, err := NewServer(cfg, store, pm)
	if err != nil {
		t.Fatalf("failed to create server: %v", err)
	}

	return srv, tmpDir
}

func TestHandleInfo(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	req := httptest.NewRequest(http.MethodGet, "/info", nil)
	rr := httptest.NewRecorder()

	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got: %d", rr.Code)
	}

	var resp InfoResponse
	if err := json.NewDecoder(rr.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}

	if resp.DeviceName != "TestDevice" {
		t.Errorf("expected DeviceName TestDevice, got %s", resp.DeviceName)
	}
	if resp.OS != "linux" {
		t.Errorf("expected OS linux, got %s", resp.OS)
	}
	if resp.Version != "1.0.0" {
		t.Errorf("expected Version 1.0.0, got %s", resp.Version)
	}
}

func TestHandleInfo_MethodNotAllowed(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	req := httptest.NewRequest(http.MethodPost, "/info", nil)
	rr := httptest.NewRecorder()

	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405 Method Not Allowed, got: %d", rr.Code)
	}
}

func TestHandleUpload_RawStream(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	payload := "Data from raw stream"
	req := httptest.NewRequest(http.MethodPost, "/upload", strings.NewReader(payload))
	req.Header.Set("X-Filename", "raw-test.txt")
	req.Header.Set("Content-Type", "application/octet-stream")
	rr := httptest.NewRecorder()

	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got: %d (%s)", rr.Code, rr.Body.String())
	}

	var resp UploadResponse
	if err := json.NewDecoder(rr.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}

	if resp.Status != "success" {
		t.Errorf("expected status success, got %s", resp.Status)
	}
	if resp.OriginalName != "raw-test.txt" {
		t.Errorf("expected original name raw-test.txt, got %s", resp.OriginalName)
	}

	content, err := os.ReadFile(resp.SavedPath)
	if err != nil {
		t.Fatalf("failed to read saved file: %v", err)
	}
	if string(content) != payload {
		t.Errorf("saved content mismatch: %s != %s", string(content), payload)
	}
}

func TestHandleUpload_Multipart(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, err := writer.CreateFormFile("file", "image.png")
	if err != nil {
		t.Fatalf("failed to create multipart form file: %v", err)
	}
	imageData := "simulated-png-bytes"
	if _, err := io.WriteString(part, imageData); err != nil {
		t.Fatalf("failed to write multipart data: %v", err)
	}
	_ = writer.Close()

	req := httptest.NewRequest(http.MethodPost, "/upload", body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	rr := httptest.NewRecorder()

	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got: %d (%s)", rr.Code, rr.Body.String())
	}

	var resp UploadResponse
	if err := json.NewDecoder(rr.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}

	if resp.SanitizedName != "image.png" {
		t.Errorf("expected image.png, got %s", resp.SanitizedName)
	}

	savedBytes, err := os.ReadFile(filepath.Join(tmpDir, "image.png"))
	if err != nil {
		t.Fatalf("failed to read saved file: %v", err)
	}
	if string(savedBytes) != imageData {
		t.Errorf("expected %s, got %s", imageData, string(savedBytes))
	}
}

func TestHandleUpload_MissingFilename(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	req := httptest.NewRequest(http.MethodPost, "/upload", strings.NewReader("content"))
	rr := httptest.NewRecorder()

	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request, got: %d", rr.Code)
	}
}

func TestHandleUpload_OversizedPayload(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 10)
	defer os.RemoveAll(tmpDir)

	oversized := strings.Repeat("A", 100)
	req := httptest.NewRequest(http.MethodPost, "/upload", strings.NewReader(oversized))
	req.Header.Set("X-Filename", "big.txt")
	rr := httptest.NewRecorder()

	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413 Payload Too Large, got: %d", rr.Code)
	}
}

func TestHandleText_Success(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	body := `{"text":"https://github.com"}`
	req := httptest.NewRequest(http.MethodPost, "/text", strings.NewReader(body))
	rr := httptest.NewRecorder()

	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got: %d", rr.Code)
	}

	var resp TextResponse
	if err := json.NewDecoder(rr.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if resp.Status != "success" {
		t.Errorf("expected status success, got %s", resp.Status)
	}
	if resp.BytesReceived != len("https://github.com") {
		t.Errorf("expected %d bytes, got %d", len("https://github.com"), resp.BytesReceived)
	}
}

func TestHandleText_EmptyOrMalformed(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	tests := []struct {
		name string
		body string
	}{
		{"empty string", `{"text":""}`},
		{"whitespace only", `{"text":"   "}`},
		{"invalid json", `{malformed}`},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, "/text", strings.NewReader(tt.body))
			rr := httptest.NewRecorder()

			srv.httpServer.Handler.ServeHTTP(rr, req)

			if rr.Code != http.StatusBadRequest {
				t.Fatalf("expected 400 Bad Request, got: %d", rr.Code)
			}
		})
	}
}

func TestPairingFlowAndAuth(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	// 1. Call /pair/start
	startReq := httptest.NewRequest(http.MethodPost, "/pair/start", nil)
	startRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(startRR, startReq)

	if startRR.Code != http.StatusOK {
		t.Fatalf("expected 200 from /pair/start, got: %d", startRR.Code)
	}

	var startResp PairStartResponse
	if err := json.NewDecoder(startRR.Body).Decode(&startResp); err != nil {
		t.Fatalf("failed to decode /pair/start response: %v", err)
	}

	if startResp.Secret == "" || !strings.HasPrefix(startResp.PairingURI, "skydrop://pair?") {
		t.Fatalf("invalid pairing start response: %+v", startResp)
	}

	// 2. Call /pair/confirm with wrong secret -> 403 Forbidden
	badConfirmReq := httptest.NewRequest(http.MethodPost, "/pair/confirm", strings.NewReader(`{"device_name":"Pixel","fingerprint":"AA","secret":"bad"}`))
	badConfirmRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(badConfirmRR, badConfirmReq)

	if badConfirmRR.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden for bad secret, got: %d", badConfirmRR.Code)
	}

	// 3. Call /pair/confirm with correct secret -> 200 OK
	confirmPayload, _ := json.Marshal(PairConfirmRequest{
		DeviceName:  "Pixel 9",
		Fingerprint: "AA:BB:CC",
		Secret:      startResp.Secret,
	})
	confirmReq := httptest.NewRequest(http.MethodPost, "/pair/confirm", bytes.NewReader(confirmPayload))
	confirmRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(confirmRR, confirmReq)

	if confirmRR.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from /pair/confirm, got: %d (%s)", confirmRR.Code, confirmRR.Body.String())
	}

	var confirmResp PairConfirmResponse
	if err := json.NewDecoder(confirmRR.Body).Decode(&confirmResp); err != nil {
		t.Fatalf("failed to decode /pair/confirm response: %v", err)
	}
	if confirmResp.AuthToken == "" {
		t.Fatal("expected non-empty auth token")
	}

	// 4. Test /pair/peers
	peersReq := httptest.NewRequest(http.MethodGet, "/pair/peers", nil)
	peersRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(peersRR, peersReq)

	if peersRR.Code != http.StatusOK {
		t.Fatalf("expected 200 from /pair/peers, got: %d", peersRR.Code)
	}

	// 5. Test Auth enforcement on /upload when RequirePairing is true
	srv.cfg.RequirePairing = true

	// 5a. Missing Authorization header -> 401 Unauthorized
	unauthReq := httptest.NewRequest(http.MethodPost, "/upload", strings.NewReader("content"))
	unauthReq.Header.Set("X-Filename", "test.txt")
	unauthRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(unauthRR, unauthReq)

	if unauthRR.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized, got: %d", unauthRR.Code)
	}

	// 5b. Invalid token -> 401 Unauthorized
	badTokenReq := httptest.NewRequest(http.MethodPost, "/upload", strings.NewReader("content"))
	badTokenReq.Header.Set("X-Filename", "test.txt")
	badTokenReq.Header.Set("Authorization", "Bearer invalid-token")
	badTokenRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(badTokenRR, badTokenReq)

	if badTokenRR.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized, got: %d", badTokenRR.Code)
	}

	// 5c. Valid Bearer token -> 200 OK
	authReq := httptest.NewRequest(http.MethodPost, "/upload", strings.NewReader("content"))
	authReq.Header.Set("X-Filename", "auth-test.txt")
	authReq.Header.Set("Authorization", "Bearer "+confirmResp.AuthToken)
	authRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(authRR, authReq)

	if authRR.Code != http.StatusOK {
		t.Fatalf("expected 200 OK with valid bearer token, got: %d (%s)", authRR.Code, authRR.Body.String())
	}
}

func TestPairingRequestWorkflow(t *testing.T) {
	srv, tmpDir := setupTestServer(t, 1024*1024)
	defer os.RemoveAll(tmpDir)

	// 1. Submit pairing request to /pair/request
	reqBody := `{"device_name":"Galaxy S24","device_type":"android","host":"192.168.1.50","port":8444,"is_tls":false}`
	req := httptest.NewRequest(http.MethodPost, "/pair/request", strings.NewReader(reqBody))
	rr := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 from /pair/request, got: %d (%s)", rr.Code, rr.Body.String())
	}

	var res struct {
		Status    string `json:"status"`
		RequestID string `json:"request_id"`
	}
	if err := json.NewDecoder(rr.Body).Decode(&res); err != nil || res.RequestID == "" {
		t.Fatalf("failed to decode /pair/request response: %v", err)
	}
	if res.Status != "pending" {
		t.Fatalf("expected status pending, got: %s", res.Status)
	}

	// 2. Query /pair/request/status -> pending
	statusReq := httptest.NewRequest(http.MethodGet, "/pair/request/status?request_id="+res.RequestID, nil)
	statusRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(statusRR, statusReq)
	if statusRR.Code != http.StatusOK {
		t.Fatalf("expected 200 from /pair/request/status, got: %d", statusRR.Code)
	}

	// 3. Query /api/pair/incoming -> should have 1 item
	incomingReq := httptest.NewRequest(http.MethodGet, "/api/pair/incoming", nil)
	incomingRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(incomingRR, incomingReq)
	if incomingRR.Code != http.StatusOK {
		t.Fatalf("expected 200 from /api/pair/incoming, got: %d", incomingRR.Code)
	}

	// 4. Accept the request via /api/pair/incoming/accept
	acceptBody := fmt.Sprintf(`{"request_id":"%s"}`, res.RequestID)
	acceptReq := httptest.NewRequest(http.MethodPost, "/api/pair/incoming/accept", strings.NewReader(acceptBody))
	acceptRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(acceptRR, acceptReq)
	if acceptRR.Code != http.StatusOK {
		t.Fatalf("expected 200 from /api/pair/incoming/accept, got: %d (%s)", acceptRR.Code, acceptRR.Body.String())
	}

	var acceptResp struct {
		Status string `json:"status"`
		Secret string `json:"secret"`
	}
	if err := json.NewDecoder(acceptRR.Body).Decode(&acceptResp); err != nil || acceptResp.Secret == "" {
		t.Fatalf("failed to decode accept response: %v", err)
	}
	if acceptResp.Status != "accepted" {
		t.Fatalf("expected status accepted, got: %s", acceptResp.Status)
	}

	// 5. Query /pair/request/status -> accepted
	stReq2 := httptest.NewRequest(http.MethodGet, "/pair/request/status?request_id="+res.RequestID, nil)
	stRR2 := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(stRR2, stReq2)
	var stData struct {
		Status string `json:"status"`
	}
	_ = json.NewDecoder(stRR2.Body).Decode(&stData)
	if stData.Status != "accepted" {
		t.Fatalf("expected status accepted on status endpoint, got: %s", stData.Status)
	}

	// 6. Complete pairing with the generated secret
	confirmBody := fmt.Sprintf(`{"device_name":"Galaxy S24","secret":"%s","host":"192.168.1.50","port":8444,"is_tls":false}`, acceptResp.Secret)
	confirmReq := httptest.NewRequest(http.MethodPost, "/pair/confirm", strings.NewReader(confirmBody))
	confirmRR := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(confirmRR, confirmReq)
	if confirmRR.Code != http.StatusOK {
		t.Fatalf("expected 200 from /pair/confirm, got: %d (%s)", confirmRR.Code, confirmRR.Body.String())
	}
}
