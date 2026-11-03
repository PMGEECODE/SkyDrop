package web

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"skydrop/daemon/internal/client"
	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/discovery"
	"skydrop/daemon/internal/history"
	"skydrop/daemon/internal/presence"
	"skydrop/daemon/internal/security"
	"skydrop/daemon/internal/ui"
)

func setupTestWebController(t *testing.T) (*WebController, *http.ServeMux, string) {
	t.Helper()
	tmpDir, err := os.MkdirTemp("", "skydrop-web-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	cfg := &config.Config{
		Port:              8443,
		DeviceName:        "TestLaptop",
		StorageDir:        tmpDir,
		PairedDevicesFile: filepath.Join(tmpDir, "paired.json"),
		HistoryFile:       filepath.Join(tmpDir, "history.json"),
	}

	pm, err := security.NewPairManager(cfg.PairedDevicesFile)
	if err != nil {
		t.Fatalf("failed to create pair manager: %v", err)
	}

	histMgr, err := history.NewManager(cfg.HistoryFile, 100)
	if err != nil {
		t.Fatalf("failed to create history manager: %v", err)
	}

	resolver := discovery.NewPeerResolver()
	uploader := client.NewUploader(true)
	notifier := ui.NewNotifier()

	controller := NewWebController(cfg, pm, resolver, uploader, notifier, histMgr)
	presService := presence.NewService(pm, resolver, "test-local", "TestLaptop")
	controller.SetPresenceService(presService)

	mux := http.NewServeMux()
	controller.RegisterRoutes(mux)

	return controller, mux, tmpDir
}

func TestWebController_Dashboard(t *testing.T) {
	_, mux, tmpDir := setupTestWebController(t)
	defer os.RemoveAll(tmpDir)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for dashboard, got %d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "SkyDrop") {
		t.Errorf("expected dashboard HTML to contain SkyDrop")
	}
}

func TestWebController_GetPeers(t *testing.T) {
	_, mux, tmpDir := setupTestWebController(t)
	defer os.RemoveAll(tmpDir)

	req := httptest.NewRequest(http.MethodGet, "/api/peers", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rr.Code)
	}

	var data map[string]interface{}
	if err := json.NewDecoder(rr.Body).Decode(&data); err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}

	if data["device"] != "TestLaptop" {
		t.Errorf("expected device TestLaptop, got %v", data["device"])
	}
}

func TestWebController_NewPairSession(t *testing.T) {
	_, mux, tmpDir := setupTestWebController(t)
	defer os.RemoveAll(tmpDir)

	req := httptest.NewRequest(http.MethodPost, "/api/pair/new", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rr.Code)
	}

	var data map[string]interface{}
	if err := json.NewDecoder(rr.Body).Decode(&data); err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}

	if data["secret"] == "" {
		t.Error("expected non-empty pairing secret")
	}
	uri, ok := data["pairing_uri"].(string)
	if !ok || !strings.HasPrefix(uri, "skydrop://pair?") {
		t.Errorf("invalid pairing URI: %v", data["pairing_uri"])
	}
}

func TestWebController_History(t *testing.T) {
	controller, mux, tmpDir := setupTestWebController(t)
	defer os.RemoveAll(tmpDir)

	// Add an entry to history
	_ = controller.history.AddEntry(history.Entry{
		Filename:  "test_item.txt",
		Direction: history.DirectionInbound,
		Size:      42,
		Status:    history.StatusCompleted,
	})

	// GET /api/history
	req := httptest.NewRequest(http.MethodGet, "/api/history", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rr.Code)
	}

	var entries []history.Entry
	if err := json.NewDecoder(rr.Body).Decode(&entries); err != nil {
		t.Fatalf("failed to decode history json: %v", err)
	}
	if len(entries) != 1 || entries[0].Filename != "test_item.txt" {
		t.Errorf("unexpected history entries: %+v", entries)
	}

	// POST /api/history/clear
	clearReq := httptest.NewRequest(http.MethodPost, "/api/history/clear", nil)
	clearRR := httptest.NewRecorder()
	mux.ServeHTTP(clearRR, clearReq)

	if clearRR.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for clear, got %d", clearRR.Code)
	}

	// Verify history is empty
	if len(controller.history.ListEntries(10)) != 0 {
		t.Errorf("expected history to be empty after clear")
	}
}

func TestWebController_GetPeers_NameNormalizationAndPairMatching(t *testing.T) {
	controller, mux, tmpDir := setupTestWebController(t)
	defer os.RemoveAll(tmpDir)

	// Pre-pair device with unescaped name
	session, err := controller.pairManager.CreatePairingSession()
	if err != nil {
		t.Fatalf("failed to create session: %v", err)
	}
	pairedPeer, err := controller.pairManager.ConfirmPairing("samsung SM-A556E", "", session.Secret)
	if err != nil {
		t.Fatalf("failed to pair test device: %v", err)
	}

	// Inject discovered peer with mDNS escaped backslash
	controller.resolver.AddCachedPeer(discovery.DiscoveredPeer{
		ID:    "peer-1",
		Name:  "samsung\\ SM-A556E",
		Host:  "192.168.74.18",
		Port:  8444,
		IsTLS: false,
		OS:    "android",
	})

	req := httptest.NewRequest(http.MethodGet, "/api/peers", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rr.Code)
	}

	var data struct {
		Peers []PeerStatusItem `json:"peers"`
	}
	if err := json.NewDecoder(rr.Body).Decode(&data); err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}

	if len(data.Peers) != 1 {
		t.Fatalf("expected 1 unified peer, got %d", len(data.Peers))
	}

	p := data.Peers[0]
	if p.Name != "samsung SM-A556E" {
		t.Errorf("expected clean display name 'samsung SM-A556E', got %q", p.Name)
	}
	if !p.IsPaired {
		t.Errorf("expected peer to be matched as Paired")
	}
	if p.PresenceState != "UNKNOWN" {
		t.Errorf("expected presence state UNKNOWN before probe, got %q", p.PresenceState)
	}
	if p.IsConnected {
		t.Errorf("expected peer NOT to be connected before probe/activity")
	}
	if p.AuthToken != pairedPeer.AuthToken {
		t.Errorf("expected valid AuthToken %q, got %q", pairedPeer.AuthToken, p.AuthToken)
	}

	// Now record activity/liveness
	controller.presenceService.RecordActivity(pairedPeer.ID)

	// Fetch again
	req2 := httptest.NewRequest(http.MethodGet, "/api/peers", nil)
	rr2 := httptest.NewRecorder()
	mux.ServeHTTP(rr2, req2)

	var data2 struct {
		Peers []PeerStatusItem `json:"peers"`
	}
	if err := json.NewDecoder(rr2.Body).Decode(&data2); err != nil {
		t.Fatalf("failed to decode json: %v", err)
	}
	p2 := data2.Peers[0]
	if !p2.IsConnected {
		t.Errorf("expected peer to be marked Connected after RecordActivity")
	}
	if p2.PresenceState != "ONLINE" {
		t.Errorf("expected presence state ONLINE after RecordActivity, got %q", p2.PresenceState)
	}
}

