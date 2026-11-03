package web

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"skydrop/daemon/internal/storage"
)

func TestWebController_SettingsLifecycle(t *testing.T) {
	controller, mux, tmpDir := setupTestWebController(t)
	defer os.RemoveAll(tmpDir)

	store, err := storage.NewStorage(tmpDir, 1024*1024*1024)
	if err != nil {
		t.Fatalf("failed to init storage: %v", err)
	}
	controller.SetStorage(store)

	// 1. GET settings
	reqGet := httptest.NewRequest(http.MethodGet, "/api/settings", nil)
	rrGet := httptest.NewRecorder()
	mux.ServeHTTP(rrGet, reqGet)

	if rrGet.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rrGet.Code)
	}

	var payload SettingsPayload
	if err := json.Unmarshal(rrGet.Body.Bytes(), &payload); err != nil {
		t.Fatalf("failed to decode settings JSON: %v", err)
	}
	if payload.DeviceName != "TestLaptop" {
		t.Errorf("expected TestLaptop, got %s", payload.DeviceName)
	}

	// 2. POST settings update
	newDir := filepath.Join(tmpDir, "new-downloads")
	newName := "RenamedLaptop"
	requirePair := false

	updateBody, _ := json.Marshal(map[string]interface{}{
		"device_name":     newName,
		"storage_dir":     newDir,
		"require_pairing": requirePair,
	})

	reqPost := httptest.NewRequest(http.MethodPost, "/api/settings", bytes.NewReader(updateBody))
	rrPost := httptest.NewRecorder()
	mux.ServeHTTP(rrPost, reqPost)

	if rrPost.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rrPost.Code)
	}

	var updated SettingsPayload
	if err := json.Unmarshal(rrPost.Body.Bytes(), &updated); err != nil {
		t.Fatalf("failed to decode updated settings JSON: %v", err)
	}

	if updated.DeviceName != newName {
		t.Errorf("expected %s, got %s", newName, updated.DeviceName)
	}
	if updated.StorageDir != newDir {
		t.Errorf("expected %s, got %s", newDir, updated.StorageDir)
	}
	if updated.RequirePairing != requirePair {
		t.Errorf("expected require_pairing false, got true")
	}

	// Verify storage base dir actually changed
	if store.GetBaseDir() != newDir {
		t.Errorf("expected storage baseDir to be %s, got %s", newDir, store.GetBaseDir())
	}
}

func TestWebController_UnpairPeer(t *testing.T) {
	controller, mux, tmpDir := setupTestWebController(t)
	defer os.RemoveAll(tmpDir)

	// Create pairing session and confirm peer
	session, err := controller.pairManager.CreatePairingSession()
	if err != nil {
		t.Fatalf("failed to create pairing session: %v", err)
	}

	peer, err := controller.pairManager.ConfirmPairing("MobileDevice", "FINGERPRINT123", session.Secret)
	if err != nil {
		t.Fatalf("failed to confirm pairing: %v", err)
	}

	if len(controller.pairManager.ListPeers()) != 1 {
		t.Fatalf("expected 1 paired peer, got %d", len(controller.pairManager.ListPeers()))
	}

	// Unpair the peer
	unpairBody, _ := json.Marshal(map[string]string{
		"id": peer.ID,
	})

	req := httptest.NewRequest(http.MethodPost, "/api/pair/unpair", bytes.NewReader(unpairBody))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rr.Code, rr.Body.String())
	}

	if len(controller.pairManager.ListPeers()) != 0 {
		t.Errorf("expected 0 paired peers after unpair, got %d", len(controller.pairManager.ListPeers()))
	}
}
