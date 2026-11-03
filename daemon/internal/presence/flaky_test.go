package presence

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"skydrop/daemon/internal/discovery"
)

func TestFlakyNetworkPresence(t *testing.T) {
	pm, tmpDir := setupTestPairManager(t)
	defer os.RemoveAll(tmpDir)

	session, _ := pm.CreatePairingSession()
	paired, err := pm.ConfirmPairing("Flaky Device", "fpFlaky", session.Secret)
	if err != nil {
		t.Fatalf("failed to confirm pairing: %v", err)
	}

	resolver := discovery.NewPeerResolver()
	service := NewService(pm, resolver, "desktop-flaky", "SkyDrop Desktop")

	var dropCount int32
	var delayDuration time.Duration

	// Simulated flaky HTTP server
	mockServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			// If delay configured, sleep longer than probe timeout
			if d := delayDuration; d > 0 {
				time.Sleep(d)
			}
			// If drop configured, return 500 error or close connection
			if atomic.LoadInt32(&dropCount) > 0 {
				atomic.AddInt32(&dropCount, -1)
				w.WriteHeader(http.StatusGatewayTimeout)
				return
			}

			resp := HealthResponse{
				DeviceID:        paired.ID,
				DeviceName:      "Flaky Device",
				AppVersion:      AppVersion,
				ProtocolVersion: ProtocolVersion,
				Capabilities:    DefaultCapabilities,
				State:           "idle",
				Fingerprint:     "fpFlaky",
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

	resolver.AddCachedPeer(discovery.DiscoveredPeer{
		ID:          paired.ID,
		Name:        "Flaky Device",
		Host:        u.Hostname(),
		Port:        port,
		IsTLS:       false,
		Fingerprint: "fpFlaky",
	})

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

	// Initial healthy probe: must become ONLINE
	service.checkAllPeers()
	p, _ := service.GetPresence(paired.ID)
	if p.State != StateOnline {
		t.Fatalf("expected ONLINE on initial healthy connection, got %s", p.State)
	}

	// Inject 2 dropped requests
	atomic.StoreInt32(&dropCount, 2)

	// First dropped probe
	service.checkAllPeers()
	p, _ = service.GetPresence(paired.ID)
	if p.MissedBeats != 1 {
		t.Fatalf("expected 1 missed beat after first drop, got %d", p.MissedBeats)
	}

	// Second dropped probe: must degrade
	service.checkAllPeers()
	p, _ = service.GetPresence(paired.ID)
	if p.State != StateDegraded {
		t.Fatalf("expected DEGRADED after second drop, got %s", p.State)
	}

	// Network recovers: dropCount is now 0
	service.checkAllPeers()
	p, _ = service.GetPresence(paired.ID)
	if p.State != StateOnline {
		t.Fatalf("expected recovery to ONLINE after flaky period, got %s", p.State)
	}
	if p.MissedBeats != 0 {
		t.Fatalf("expected missed beats reset to 0, got %d", p.MissedBeats)
	}
}
