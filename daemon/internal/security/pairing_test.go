package security

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPairManager_LifecycleAndPersistence(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-pair-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	storePath := filepath.Join(tmpDir, "paired_devices.json")
	pm, err := NewPairManager(storePath)
	if err != nil {
		t.Fatalf("NewPairManager failed: %v", err)
	}

	if pm.HasPairedPeers() {
		t.Error("expected 0 paired peers initially")
	}

	// 1. Create pairing session
	session, err := pm.CreatePairingSession()
	if err != nil {
		t.Fatalf("CreatePairingSession failed: %v", err)
	}
	if len(session.Secret) != 6 {
		t.Errorf("expected 6-digit numeric PIN secret, got %s", session.Secret)
	}

	// 2. Reject incorrect secret
	_, err = pm.ConfirmPairing("Pixel 9", "11:22:33", "wrong_secret")
	if !errors.Is(err, ErrInvalidPairingSecret) {
		t.Fatalf("expected ErrInvalidPairingSecret, got: %v", err)
	}

	// 3. Confirm with valid secret
	peer, err := pm.ConfirmPairing("Pixel 9", "11:22:33:44", session.Secret)
	if err != nil {
		t.Fatalf("ConfirmPairing failed: %v", err)
	}
	if peer.DeviceName != "Pixel 9" {
		t.Errorf("expected Pixel 9, got %s", peer.DeviceName)
	}
	if len(peer.AuthToken) != 64 {
		t.Errorf("expected 64-char token, got %s", peer.AuthToken)
	}

	// 4. Session should now be consumed / single-use
	_, err = pm.ConfirmPairing("Another Device", "55:66", session.Secret)
	if !errors.Is(err, ErrPairingExpired) {
		t.Fatalf("expected ErrPairingExpired on reused secret, got: %v", err)
	}

	// 5. Token validation
	validPeer, err := pm.ValidateToken(peer.AuthToken)
	if err != nil {
		t.Fatalf("ValidateToken failed: %v", err)
	}
	if validPeer.ID != peer.ID {
		t.Errorf("peer ID mismatch")
	}

	// Reject bogus token
	_, err = pm.ValidateToken("invalid-token")
	if !errors.Is(err, ErrUnauthorizedPeer) {
		t.Fatalf("expected ErrUnauthorizedPeer, got: %v", err)
	}

	// 6. Reload from disk and verify persistence
	pm2, err := NewPairManager(storePath)
	if err != nil {
		t.Fatalf("reload NewPairManager failed: %v", err)
	}
	if !pm2.HasPairedPeers() {
		t.Error("expected paired peers to persist after reload")
	}
	reloadedPeer, err := pm2.ValidateToken(peer.AuthToken)
	if err != nil {
		t.Fatalf("ValidateToken on reloaded pm failed: %v", err)
	}
	if reloadedPeer.DeviceName != "Pixel 9" {
		t.Errorf("expected Pixel 9, got %s", reloadedPeer.DeviceName)
	}
}

func TestPairManager_SessionExpiration(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-pair-exp-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	pm, err := NewPairManager(filepath.Join(tmpDir, "paired.json"))
	if err != nil {
		t.Fatalf("NewPairManager failed: %v", err)
	}

	session, err := pm.CreatePairingSession()
	if err != nil {
		t.Fatalf("CreatePairingSession failed: %v", err)
	}

	// Artificially expire the session
	pm.mu.Lock()
	pm.activeSession.ExpiresAt = time.Now().UTC().Add(-1 * time.Second)
	pm.mu.Unlock()

	_, err = pm.ConfirmPairing("Device", "FP", session.Secret)
	if !errors.Is(err, ErrPairingExpired) {
		t.Fatalf("expected ErrPairingExpired, got: %v", err)
	}
}
