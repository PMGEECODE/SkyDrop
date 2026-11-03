package security

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

var mdnsSuffixRegex = regexp.MustCompile(`(?i)\s*[\(_#]\d+[\)]?$`)

// NormalizeDeviceName strips mDNS conflict suffixes like " (2)" and whitespace for resilient matching.
func NormalizeDeviceName(n string) string {
	clean := strings.ToLower(strings.TrimSpace(strings.ReplaceAll(n, "\\", "")))
	return strings.TrimSpace(mdnsSuffixRegex.ReplaceAllString(clean, ""))
}

const (
	PairingSecretLength = 6  // 6 digits PIN
	AuthTokenLength     = 32 // 32 bytes = 64 hex chars
	PairingSessionTTL   = 5 * time.Minute
)

var (
	ErrPairingExpired       = errors.New("pairing session expired or invalid")
	ErrInvalidPairingSecret = errors.New("invalid pairing secret")
	ErrPeerAlreadyPaired    = errors.New("peer is already paired")
	ErrUnauthorizedPeer     = errors.New("unauthorized: missing or invalid authentication token")
)

// PairedPeer represents an authenticated, trusted peer device.
type PairedPeer struct {
	ID          string    `json:"id"`
	DeviceName  string    `json:"device_name"`
	Fingerprint string    `json:"fingerprint"`
	AuthToken   string    `json:"auth_token"`
	PairedAt    time.Time `json:"paired_at"`
	LastSeenAt  time.Time `json:"last_seen_at"`
}

// PairingSession represents an ephemeral one-time pairing attempt.
type PairingSession struct {
	Secret    string    `json:"secret"`
	ExpiresAt time.Time `json:"expires_at"`
}

// PairManager manages paired devices and ephemeral pairing sessions.
type PairManager struct {
	mu            sync.RWMutex
	storePath     string
	peers         map[string]*PairedPeer // keyed by AuthToken
	peerByFP      map[string]*PairedPeer // keyed by Fingerprint
	activeSession *PairSessionData
	listeners     []func(peer *PairedPeer)
}

type PairSessionData struct {
	Secret    string
	ExpiresAt time.Time
}

// NewPairManager initializes PairManager and loads persisted peers from disk.
func NewPairManager(storePath string) (*PairManager, error) {
	pm := &PairManager{
		storePath: storePath,
		peers:     make(map[string]*PairedPeer),
		peerByFP:  make(map[string]*PairedPeer),
	}

	if err := os.MkdirAll(filepath.Dir(storePath), 0700); err != nil {
		return nil, fmt.Errorf("failed to create directory for paired devices: %w", err)
	}

	if err := pm.loadFromDisk(); err != nil {
		return nil, err
	}

	return pm, nil
}

func (pm *PairManager) loadFromDisk() error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	data, err := os.ReadFile(pm.storePath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("failed to read paired devices file: %w", err)
	}

	var peerList []*PairedPeer
	if err := json.Unmarshal(data, &peerList); err != nil {
		return fmt.Errorf("failed to parse paired devices JSON: %w", err)
	}

	for _, p := range peerList {
		cleanName := NormalizeDeviceName(p.DeviceName)
		// If already present with same device name, keep the one with latest PairedAt
		var replacedToken string
		for token, existing := range pm.peers {
			if NormalizeDeviceName(existing.DeviceName) == cleanName {
				if p.PairedAt.After(existing.PairedAt) || p.LastSeenAt.After(existing.LastSeenAt) {
					replacedToken = token
				} else {
					// Existing is newer; don't replace
					cleanName = ""
					break
				}
			}
		}
		if cleanName != "" {
			if replacedToken != "" {
				delete(pm.peers, replacedToken)
			}
			pm.peers[p.AuthToken] = p
			if p.Fingerprint != "" {
				pm.peerByFP[p.Fingerprint] = p
			}
		}
	}

	return nil
}

func (pm *PairManager) saveToDiskLocked() error {
	peerList := make([]*PairedPeer, 0, len(pm.peers))
	for _, p := range pm.peers {
		peerList = append(peerList, p)
	}

	data, err := json.MarshalIndent(peerList, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to serialize paired devices: %w", err)
	}

	tempPath := pm.storePath + ".tmp"
	if err := os.WriteFile(tempPath, data, 0600); err != nil {
		return fmt.Errorf("failed to write temp paired devices file: %w", err)
	}

	if err := os.Rename(tempPath, pm.storePath); err != nil {
		_ = os.Remove(tempPath)
		return fmt.Errorf("failed to commit paired devices file: %w", err)
	}

	return nil
}

// CreatePairingSession generates a new 6-digit one-time secret PIN valid for 5 minutes.
func (pm *PairManager) CreatePairingSession() (*PairingSession, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return nil, fmt.Errorf("failed to generate secure random PIN: %w", err)
	}
	secret := fmt.Sprintf("%06d", n.Int64())
	expiresAt := time.Now().UTC().Add(PairingSessionTTL)

	pm.activeSession = &PairSessionData{
		Secret:    secret,
		ExpiresAt: expiresAt,
	}

	return &PairingSession{
		Secret:    secret,
		ExpiresAt: expiresAt,
	}, nil
}

// ConfirmPairing verifies the one-time secret and issues a long-lived auth token to the peer.
func (pm *PairManager) ConfirmPairing(deviceName, peerFingerprint, secret string) (*PairedPeer, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if pm.activeSession == nil || time.Now().UTC().After(pm.activeSession.ExpiresAt) {
		return nil, ErrPairingExpired
	}

	// Constant-time secret comparison
	if subtle.ConstantTimeCompare([]byte(pm.activeSession.Secret), []byte(secret)) != 1 {
		return nil, ErrInvalidPairingSecret
	}

	// Generate long-lived auth token
	tokenBytes := make([]byte, AuthTokenLength)
	if _, err := rand.Read(tokenBytes); err != nil {
		return nil, fmt.Errorf("failed to generate auth token: %w", err)
	}
	token := hex.EncodeToString(tokenBytes)

	peer := &PairedPeer{
		ID:          hex.EncodeToString(tokenBytes[:8]),
		DeviceName:  deviceName,
		Fingerprint: peerFingerprint,
		AuthToken:   token,
		PairedAt:    time.Now().UTC(),
		LastSeenAt:  time.Now().UTC(),
	}

	// Deduplicate any older pairing sessions for the same device name or fingerprint
	cleanName := NormalizeDeviceName(deviceName)
	for oldToken, existing := range pm.peers {
		if (peerFingerprint != "" && strings.EqualFold(existing.Fingerprint, peerFingerprint)) ||
			(cleanName != "" && NormalizeDeviceName(existing.DeviceName) == cleanName) {
			delete(pm.peers, oldToken)
			if existing.Fingerprint != "" {
				delete(pm.peerByFP, existing.Fingerprint)
			}
		}
	}

	pm.peers[token] = peer
	if peerFingerprint != "" {
		pm.peerByFP[peerFingerprint] = peer
	}
	// Invalidate one-time session upon successful pairing
	pm.activeSession = nil

	if err := pm.saveToDiskLocked(); err != nil {
		return nil, err
	}

	callbacks := make([]func(peer *PairedPeer), len(pm.listeners))
	copy(callbacks, pm.listeners)
	peerCopy := *peer

	go func() {
		for _, cb := range callbacks {
			if cb != nil {
				cb(&peerCopy)
			}
		}
	}()

	return peer, nil
}

// OnPairSuccess registers a listener callback invoked whenever a remote peer confirms pairing.
func (pm *PairManager) OnPairSuccess(fn func(peer *PairedPeer)) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	pm.listeners = append(pm.listeners, fn)
}

// GetPeerByDeviceName returns the most recently seen paired peer for a given device name.
func (pm *PairManager) GetPeerByDeviceName(deviceName string) *PairedPeer {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	var bestMatch *PairedPeer
	cleanTarget := NormalizeDeviceName(deviceName)
	for _, p := range pm.peers {
		cleanCurrent := NormalizeDeviceName(p.DeviceName)
		if cleanCurrent == cleanTarget || strings.Contains(cleanCurrent, cleanTarget) || strings.Contains(cleanTarget, cleanCurrent) {
			if bestMatch == nil || p.LastSeenAt.After(bestMatch.LastSeenAt) || p.PairedAt.After(bestMatch.PairedAt) {
				bestMatch = p
			}
		}
	}
	return bestMatch
}

// GetPeerByID returns the paired peer with matching ID.
func (pm *PairManager) GetPeerByID(id string) *PairedPeer {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	for _, p := range pm.peers {
		if p.ID == id {
			return p
		}
	}
	return nil
}

// ValidateToken verifies whether the bearer token belongs to an authorized peer.
func (pm *PairManager) ValidateToken(token string) (*PairedPeer, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	peer, exists := pm.peers[token]
	if !exists {
		return nil, ErrUnauthorizedPeer
	}

	peer.LastSeenAt = time.Now().UTC()
	return peer, nil
}

// HasActiveSession returns true if an unexpired pairing session is currently pending.
func (pm *PairManager) HasActiveSession() bool {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return pm.activeSession != nil && time.Now().UTC().Before(pm.activeSession.ExpiresAt)
}

// HasPairedPeers returns true if at least one peer is authorized.
func (pm *PairManager) HasPairedPeers() bool {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return len(pm.peers) > 0
}

// ListPeers returns a copy of all authorized peers.
func (pm *PairManager) ListPeers() []*PairedPeer {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	res := make([]*PairedPeer, 0, len(pm.peers))
	for _, p := range pm.peers {
		res = append(res, p)
	}
	sort.Slice(res, func(i, j int) bool {
		return res[i].PairedAt.After(res[j].PairedAt)
	})
	return res
}

// UnpairPeer removes an authorized peer by its ID or AuthToken.
func (pm *PairManager) UnpairPeer(idOrToken string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	var targetToken string
	for token, p := range pm.peers {
		if p.ID == idOrToken || p.AuthToken == idOrToken {
			targetToken = token
			if p.Fingerprint != "" {
				delete(pm.peerByFP, p.Fingerprint)
			}
			break
		}
	}

	if targetToken == "" {
		return errors.New("peer not found")
	}

	delete(pm.peers, targetToken)
	return pm.saveToDiskLocked()
}
