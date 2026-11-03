package presence

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"skydrop/daemon/internal/discovery"
	"skydrop/daemon/internal/security"
)

func setupTestPairManager(t *testing.T) (*security.PairManager, string) {
	tmpDir, err := os.MkdirTemp("", "skydrop-presence-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	storePath := filepath.Join(tmpDir, "paired.json")
	pm, err := security.NewPairManager(storePath)
	if err != nil {
		t.Fatalf("failed to create pair manager: %v", err)
	}
	return pm, tmpDir
}

func TestPresenceTransitions(t *testing.T) {
	pm, tmpDir := setupTestPairManager(t)
	defer os.RemoveAll(tmpDir)

	// Create a paired peer in PairManager
	session, _ := pm.CreatePairingSession()
	paired, err := pm.ConfirmPairing("Test Phone", "fp123", session.Secret)
	if err != nil {
		t.Fatalf("failed to confirm pairing: %v", err)
	}

	resolver := discovery.NewPeerResolver()
	service := NewService(pm, resolver, "desktop-1", "SkyDrop Desktop")

	// 1. Initial State MUST be UNKNOWN
	all := service.GetAllPresence()
	if len(all) != 0 {
		t.Fatalf("expected 0 active presences before loop or check, got %d", len(all))
	}

	// Mock peer HTTP server for /health
	healthCalled := false
	serverState := "idle"
	mockServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			healthCalled = true
			resp := HealthResponse{
				DeviceID:        paired.ID,
				DeviceName:      "Test Phone",
				AppVersion:      AppVersion,
				ProtocolVersion: ProtocolVersion,
				Capabilities:    DefaultCapabilities,
				State:           serverState,
				Fingerprint:     "fp123",
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(resp)
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer mockServer.Close()

	u, _ := url.Parse(mockServer.URL)
	port, _ := strconv.Atoi(u.Port())

	// Add discovered entry to resolver
	resolver.AddCachedPeer(discovery.DiscoveredPeer{
		ID:          paired.ID,
		Name:        "Test Phone",
		Host:        u.Hostname(),
		Port:        port,
		IsTLS:       false,
		Fingerprint: "fp123",
	})

	// 2. An mDNS entry alone DOES NOT mark the device ONLINE
	service.mu.Lock()
	service.peers[paired.ID] = &PeerPresence{
		PeerID:     paired.ID,
		DeviceName: paired.DeviceName,
		State:      StateUnknown,
		Host:       u.Hostname(),
		Port:       port,
		IsPaired:   true,
	}
	service.mu.Unlock()

	p, ok := service.GetPresence(paired.ID)
	if !ok || p.State == StateOnline {
		t.Fatalf("critical rule violation: device was marked online without health check: state=%s", p.State)
	}

	// 3. Probing with successful health response moves state to ONLINE
	service.checkAllPeers()
	if !healthCalled {
		t.Fatalf("expected /health to be requested")
	}

	p, _ = service.GetPresence(paired.ID)
	if p.State != StateOnline {
		t.Fatalf("expected state to be ONLINE after successful probe, got %s", p.State)
	}
	if p.MissedBeats != 0 {
		t.Fatalf("expected 0 missed beats, got %d", p.MissedBeats)
	}

	// 4. Simulate server going down (close mock server)
	mockServer.Close()

	// First missed beat
	service.checkAllPeers()
	p, _ = service.GetPresence(paired.ID)
	if p.MissedBeats != 1 {
		t.Fatalf("expected 1 missed beat, got %d", p.MissedBeats)
	}

	// Second missed beat: MUST be DEGRADED
	service.checkAllPeers()
	p, _ = service.GetPresence(paired.ID)
	if p.State != StateDegraded {
		t.Fatalf("expected DEGRADED after 2 missed beats, got %s", p.State)
	}

	// Third missed beat: MUST be OFFLINE
	service.checkAllPeers()
	p, _ = service.GetPresence(paired.ID)
	if p.State != StateOffline {
		t.Fatalf("expected OFFLINE after 3 missed beats, got %s", p.State)
	}

	// 5. Transfer progress liveness
	service.RecordActivity(paired.ID)
	p, _ = service.GetPresence(paired.ID)
	if p.State != StateOnline {
		t.Fatalf("expected RecordActivity to restore ONLINE state, got %s", p.State)
	}
	if p.MissedBeats != 0 {
		t.Fatalf("expected RecordActivity to reset missed beats to 0, got %d", p.MissedBeats)
	}

	// 6. Graceful goodbye immediately sets OFFLINE
	service.HandlePeerLeave(paired.ID, "user closed app")
	p, _ = service.GetPresence(paired.ID)
	if p.State != StateOffline {
		t.Fatalf("expected OFFLINE immediately after HandlePeerLeave, got %s", p.State)
	}
}
