package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"skydrop/daemon/internal/clipboard"
	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/security"
	"skydrop/daemon/internal/storage"
)

func setupTestServerWithClipboard(t *testing.T) (*Server, func()) {
	t.Helper()
	tmpDir := t.TempDir()

	cfg := &config.Config{
		Port:                  8443,
		DeviceName:            "TestLinuxServer",
		StorageDir:            filepath.Join(tmpDir, "downloads"),
		CertFile:              filepath.Join(tmpDir, "cert.pem"),
		KeyFile:               filepath.Join(tmpDir, "key.pem"),
		MaxFileSize:           10 * 1024 * 1024,
		EnableTLS:             false,
		PairedDevicesFile:     filepath.Join(tmpDir, "paired.json"),
		HistoryFile:           filepath.Join(tmpDir, "history.json"),
		ClipboardFile:         filepath.Join(tmpDir, "clipboard.json"),
		ClipboardSettingsFile: filepath.Join(tmpDir, "clipboard_settings.json"),
		RequirePairing:        true,
	}

	store, err := storage.NewStorage(cfg.StorageDir, cfg.MaxFileSize)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	pm, err := security.NewPairManager(cfg.PairedDevicesFile)
	if err != nil {
		t.Fatalf("failed to create pair manager: %v", err)
	}

	s, err := NewServer(cfg, store, pm)
	if err != nil {
		t.Fatalf("failed to create server: %v", err)
	}

	cleanup := func() {
		_ = os.RemoveAll(tmpDir)
		if s.clipboardWatcher != nil {
			s.clipboardWatcher.Stop()
		}
	}

	return s, cleanup
}

func TestClipboardHandlers_AddAndList(t *testing.T) {
	s, cleanup := setupTestServerWithClipboard(t)
	defer cleanup()

	// 1. Add item via POST /clipboard/add
	addBody := map[string]string{
		"text":          "Sample copied text",
		"source_device": "LocalDevice",
	}
	bodyBytes, _ := json.Marshal(addBody)

	req := httptest.NewRequest(http.MethodPost, "/clipboard/add", bytes.NewReader(bodyBytes))
	req.RemoteAddr = "127.0.0.1:54321" // Loopback bypasses auth
	rec := httptest.NewRecorder()

	s.handleClipboardAdd(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from /clipboard/add, got %d: %s", rec.Code, rec.Body.String())
	}

	// 2. List items via GET /clipboard/list
	listReq := httptest.NewRequest(http.MethodGet, "/clipboard/list", nil)
	listReq.RemoteAddr = "127.0.0.1:54321"
	listRec := httptest.NewRecorder()

	s.handleClipboardList(listRec, listReq)
	if listRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from /clipboard/list, got %d: %s", listRec.Code, listRec.Body.String())
	}

	var listResp struct {
		Items []clipboard.ClipboardItem `json:"items"`
		Count int                       `json:"count"`
	}
	if err := json.NewDecoder(listRec.Body).Decode(&listResp); err != nil {
		t.Fatalf("failed to decode list response: %v", err)
	}

	if listResp.Count != 1 || len(listResp.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", listResp.Count)
	}
	if listResp.Items[0].Text != "Sample copied text" {
		t.Fatalf("expected text 'Sample copied text', got %q", listResp.Items[0].Text)
	}
}

func TestClipboardHandlers_ValidationAndAuth(t *testing.T) {
	s, cleanup := setupTestServerWithClipboard(t)
	defer cleanup()

	// External network request without auth token should be rejected with 401
	req := httptest.NewRequest(http.MethodGet, "/clipboard/list", nil)
	req.RemoteAddr = "192.168.1.100:54321" // External IP
	rec := httptest.NewRecorder()

	// Call through requireAuth middleware
	handler := s.requireAuth(s.handleClipboardList)
	handler(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized for unauthenticated external request, got %d", rec.Code)
	}

	// Empty text validation
	emptyBody, _ := json.Marshal(map[string]string{"text": "   "})
	addReq := httptest.NewRequest(http.MethodPost, "/clipboard/add", bytes.NewReader(emptyBody))
	addReq.RemoteAddr = "127.0.0.1:54321"
	addRec := httptest.NewRecorder()

	s.handleClipboardAdd(addRec, addReq)
	if addRec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request for empty text, got %d", addRec.Code)
	}
}

func TestClipboardHandlers_PinDeleteClear(t *testing.T) {
	s, cleanup := setupTestServerWithClipboard(t)
	defer cleanup()

	// Add an item
	item, err := s.clipboardMgr.AddLocal("Persistent note", "TestDevice")
	if err != nil {
		t.Fatalf("AddLocal failed: %v", err)
	}

	// Pin it
	pinBody, _ := json.Marshal(map[string]interface{}{"id": item.ID, "pinned": true})
	pinReq := httptest.NewRequest(http.MethodPost, "/clipboard/pin", bytes.NewReader(pinBody))
	pinReq.RemoteAddr = "127.0.0.1:54321"
	pinRec := httptest.NewRecorder()
	s.handleClipboardPin(pinRec, pinReq)
	if pinRec.Code != http.StatusOK {
		t.Fatalf("Pin failed: %d %s", pinRec.Code, pinRec.Body.String())
	}

	stored, found := s.clipboardMgr.Store().GetByID(item.ID)
	if !found || !stored.Pinned {
		t.Fatalf("expected item to be pinned")
	}

	// Clear with keep_pinned=true
	clearBody, _ := json.Marshal(map[string]bool{"keep_pinned": true})
	clearReq := httptest.NewRequest(http.MethodPost, "/clipboard/clear", bytes.NewReader(clearBody))
	clearReq.RemoteAddr = "127.0.0.1:54321"
	clearRec := httptest.NewRecorder()
	s.handleClipboardClear(clearRec, clearReq)
	if clearRec.Code != http.StatusOK {
		t.Fatalf("Clear failed: %d", clearRec.Code)
	}

	// Pinned item should still exist
	if _, found := s.clipboardMgr.Store().GetByID(item.ID); !found {
		t.Fatalf("expected pinned item to survive keep_pinned=true clear")
	}

	// Delete item
	delBody, _ := json.Marshal(map[string]string{"id": item.ID})
	delReq := httptest.NewRequest(http.MethodPost, "/clipboard/delete", bytes.NewReader(delBody))
	delReq.RemoteAddr = "127.0.0.1:54321"
	delRec := httptest.NewRecorder()
	s.handleClipboardDelete(delRec, delReq)
	if delRec.Code != http.StatusOK {
		t.Fatalf("Delete failed: %d", delRec.Code)
	}

	if _, found := s.clipboardMgr.Store().GetByID(item.ID); found {
		t.Fatalf("expected item to be deleted")
	}
}
