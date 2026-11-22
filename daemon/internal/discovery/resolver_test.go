package discovery

import (
	"testing"
	"time"
)

func TestDiscoveredPeer_BaseURL(t *testing.T) {
	p1 := DiscoveredPeer{
		ID:    "192.168.1.100:8443",
		Name:  "Phone",
		Host:  "192.168.1.100",
		Port:  8443,
		IsTLS: true,
	}
	if p1.BaseURL() != "https://192.168.1.100:8443" {
		t.Errorf("expected https url, got: %s", p1.BaseURL())
	}

	p2 := DiscoveredPeer{
		ID:    "192.168.1.50:8444",
		Name:  "Android Phone",
		Host:  "192.168.1.50",
		Port:  8444,
		IsTLS: false,
	}
	if p2.BaseURL() != "http://192.168.1.50:8444" {
		t.Errorf("expected http url, got: %s", p2.BaseURL())
	}
}

func TestPeerResolver_Cache(t *testing.T) {
	pr := NewPeerResolver()
	if len(pr.GetCachedPeers()) != 0 {
		t.Errorf("expected 0 cached peers initially")
	}

	peer := DiscoveredPeer{
		ID:       "192.168.1.50:8444",
		Name:     "Pixel 9",
		Host:     "192.168.1.50",
		Port:     8444,
		IsTLS:    false,
		LastSeen: time.Now().UTC(),
	}

	pr.mu.Lock()
	pr.peers[peer.ID] = peer
	pr.mu.Unlock()

	cached := pr.GetCachedPeers()
	if len(cached) != 1 {
		t.Fatalf("expected 1 cached peer, got %d", len(cached))
	}
	if cached[0].Name != "Pixel 9" {
		t.Errorf("expected peer name Pixel 9, got %s", cached[0].Name)
	}
}

func TestPeerResolver_SelfFiltering(t *testing.T) {
	pr := NewPeerResolver()
	pr.SetSelf("MyLaptop", 8443, "AA:BB:CC:DD")

	selfByName := DiscoveredPeer{
		ID:          "192.168.1.100:8443",
		Name:        "MyLaptop",
		Host:        "192.168.1.100",
		Port:        8443,
		Fingerprint: "XX:YY:ZZ",
	}
	if !pr.IsSelf(selfByName) {
		t.Errorf("expected selfByName to be identified as self")
	}

	selfByFingerprint := DiscoveredPeer{
		ID:          "192.168.1.101:8443",
		Name:        "RenamedHost",
		Host:        "192.168.1.101",
		Port:        8443,
		Fingerprint: "AA:BB:CC:DD",
	}
	if !pr.IsSelf(selfByFingerprint) {
		t.Errorf("expected selfByFingerprint to be identified as self")
	}

	remotePeer := DiscoveredPeer{
		ID:          "192.168.1.200:8444",
		Name:        "AndroidPhone",
		Host:        "192.168.1.200",
		Port:        8444,
		Fingerprint: "11:22:33:44",
	}
	if pr.IsSelf(remotePeer) {
		t.Errorf("expected remotePeer to NOT be identified as self")
	}

	pr.mu.Lock()
	pr.peers[selfByName.ID] = selfByName
	pr.peers[remotePeer.ID] = remotePeer
	pr.mu.Unlock()

	filtered := pr.GetCachedPeers()
	if len(filtered) != 1 {
		t.Fatalf("expected 1 peer after self-filtering, got %d", len(filtered))
	}
	if filtered[0].Name != "AndroidPhone" {
		t.Errorf("expected only AndroidPhone, got %s", filtered[0].Name)
	}
}

func TestPeerResolver_PruningAndDeduplication(t *testing.T) {
	pr := NewPeerResolver()

	now := time.Now().UTC()
	stalePeer := DiscoveredPeer{
		ID:       "old-peer-id",
		Name:     "Galaxy A55",
		Host:     "192.168.1.100",
		Port:     8444,
		LastSeen: now.Add(-10 * time.Minute), // 10 minutes old
	}
	activePeer := DiscoveredPeer{
		ID:       "new-peer-id",
		Name:     "Galaxy A55",
		Host:     "192.168.1.100",
		Port:     8444,
		LastSeen: now,
	}

	pr.mu.Lock()
	pr.peers[stalePeer.ID] = stalePeer
	pr.peers[activePeer.ID] = activePeer
	pr.mu.Unlock()

	cached := pr.GetCachedPeers()
	if len(cached) != 1 {
		t.Fatalf("expected exactly 1 peer after pruning and deduplication, got %d", len(cached))
	}
	if cached[0].ID != "new-peer-id" {
		t.Errorf("expected active peer 'new-peer-id', got '%s'", cached[0].ID)
	}
}
