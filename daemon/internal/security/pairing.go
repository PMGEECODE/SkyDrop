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
	DeviceType  string    `json:"device_type,omitempty"`
	Host        string    `json:"host,omitempty"`
	Port        int       `json:"port,omitempty"`
	IsTLS       bool      `json:"is_tls,omitempty"`
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

// PairRequest represents an incoming or outgoing pairing negotiation request.
type PairRequest struct {
	ID          string    `json:"request_id"`
	DeviceID    string    `json:"device_id"`
	DeviceName  string    `json:"device_name"`
	DeviceType  string    `json:"device_type"`
	Host        string    `json:"host"`
	Port        int       `json:"port"`
	IsTLS       bool      `json:"is_tls"`
	Fingerprint string    `json:"fingerprint"`
	Status      string    `json:"status"` // pending, accepted, declined, cancelled, expired
	CreatedAt   time.Time `json:"created_at"`
	ExpiresAt   time.Time `json:"expires_at"`
}

// PairManager manages paired devices and ephemeral pairing sessions.
type PairManager struct {
	mu               sync.RWMutex
	storePath        string
	peers            map[string]*PairedPeer // keyed by AuthToken
	peerByFP         map[string]*PairedPeer // keyed by Fingerprint
	activeSession    *PairSessionData
	incomingRequests map[string]*PairRequest // keyed by request_id
	listeners        []func(peer *PairedPeer)
	requestListeners []func(req *PairRequest)
}

type PairSessionData struct {
	Secret          string
	ExpiresAt       time.Time
	FailedAttempts  int
	AssociatedReqID string
}

// NewPairManager initializes PairManager and loads persisted peers from disk.
func NewPairManager(storePath string) (*PairManager, error) {
	pm := &PairManager{
		storePath:        storePath,
		peers:            make(map[string]*PairedPeer),
		peerByFP:         make(map[string]*PairedPeer),
		incomingRequests: make(map[string]*PairRequest),
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
		if p.AuthToken == "" {
			continue
		}
		// If already present with same ID, keep the one with latest PairedAt
		var replacedToken string
		for token, existing := range pm.peers {
			if existing.ID != "" && p.ID != "" && existing.ID == p.ID {
				if p.PairedAt.After(existing.PairedAt) || p.LastSeenAt.After(existing.LastSeenAt) {
					replacedToken = token
				}
				break
			}
		}
		if replacedToken != "" {
			delete(pm.peers, replacedToken)
		}
		pm.peers[p.AuthToken] = p
		if p.Fingerprint != "" {
			pm.peerByFP[p.Fingerprint] = p
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
	return pm.ConfirmPairingWithEndpoint(deviceName, peerFingerprint, secret, "", 0, false, "", "")
}

// ConfirmPairingWithEndpoint verifies secret and registers peer with known network address.
func (pm *PairManager) ConfirmPairingWithEndpoint(deviceName, peerFingerprint, secret, host string, port int, isTLS bool, deviceType, deviceID string) (*PairedPeer, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if pm.activeSession == nil || time.Now().UTC().After(pm.activeSession.ExpiresAt) {
		return nil, ErrPairingExpired
	}

	if pm.activeSession.FailedAttempts >= 5 {
		pm.activeSession = nil
		return nil, errors.New("maximum pairing attempts exceeded; session terminated")
	}

	// Constant-time secret comparison
	if subtle.ConstantTimeCompare([]byte(pm.activeSession.Secret), []byte(secret)) != 1 {
		pm.activeSession.FailedAttempts++
		if pm.activeSession.FailedAttempts >= 5 {
			pm.activeSession = nil
			return nil, errors.New("maximum pairing attempts exceeded; session terminated")
		}
		return nil, ErrInvalidPairingSecret
	}

	// Generate long-lived auth token
	tokenBytes := make([]byte, AuthTokenLength)
	if _, err := rand.Read(tokenBytes); err != nil {
		return nil, fmt.Errorf("failed to generate auth token: %w", err)
	}
	token := hex.EncodeToString(tokenBytes)

	pid := hex.EncodeToString(tokenBytes[:8])
	if deviceID != "" {
		pid = deviceID
	}

	peer := &PairedPeer{
		ID:          pid,
		DeviceName:  deviceName,
		DeviceType:  deviceType,
		Host:        host,
		Port:        port,
		IsTLS:       isTLS,
		Fingerprint: peerFingerprint,
		AuthToken:   token,
		PairedAt:    time.Now().UTC(),
		LastSeenAt:  time.Now().UTC(),
	}

	// Deduplicate any older pairing sessions for the same fingerprint or explicit device ID
	for oldToken, existing := range pm.peers {
		if (peerFingerprint != "" && strings.EqualFold(existing.Fingerprint, peerFingerprint)) ||
			(deviceID != "" && existing.ID == deviceID) {
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

// AddPairedPeer saves an outbound paired peer and persists to disk.
func (pm *PairManager) AddPairedPeer(peer *PairedPeer) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	for oldToken, existing := range pm.peers {
		if (peer.Fingerprint != "" && strings.EqualFold(existing.Fingerprint, peer.Fingerprint)) ||
			(peer.ID != "" && existing.ID == peer.ID) {
			delete(pm.peers, oldToken)
			if existing.Fingerprint != "" {
				delete(pm.peerByFP, existing.Fingerprint)
			}
		}
	}

	pm.peers[peer.AuthToken] = peer
	if peer.Fingerprint != "" {
		pm.peerByFP[peer.Fingerprint] = peer
	}
	if err := pm.saveToDiskLocked(); err != nil {
		return err
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
	return nil
}

// OnPairRequest registers a callback invoked when a new incoming pairing request arrives.
func (pm *PairManager) OnPairRequest(fn func(req *PairRequest)) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	pm.requestListeners = append(pm.requestListeners, fn)
}

// AddIncomingRequest registers an incoming pair request pending user approval.
func (pm *PairManager) AddIncomingRequest(req PairRequest) (*PairRequest, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	// Clean expired requests
	now := time.Now().UTC()
	for id, r := range pm.incomingRequests {
		if now.After(r.ExpiresAt) {
			delete(pm.incomingRequests, id)
		}
	}

	if req.ID == "" {
		n, _ := rand.Int(rand.Reader, big.NewInt(1000000000))
		req.ID = fmt.Sprintf("req-%x", n.Int64())
	}
	req.Status = "pending"
	req.CreatedAt = now
	req.ExpiresAt = now.Add(90 * time.Second) // 90 second TTL for approval

	pm.incomingRequests[req.ID] = &req
	reqCopy := req

	listeners := make([]func(req *PairRequest), len(pm.requestListeners))
	copy(listeners, pm.requestListeners)

	go func() {
		for _, cb := range listeners {
			if cb != nil {
				cb(&reqCopy)
			}
		}
	}()

	return &reqCopy, nil
}

// GetIncomingRequest returns the request matching the given ID.
func (pm *PairManager) GetIncomingRequest(reqID string) (*PairRequest, bool) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	req, ok := pm.incomingRequests[reqID]
	if !ok {
		return nil, false
	}
	if time.Now().UTC().After(req.ExpiresAt) && req.Status == "pending" {
		req.Status = "expired"
	}
	copyReq := *req
	return &copyReq, true
}

// ListIncomingRequests returns all active pending pairing requests.
func (pm *PairManager) ListIncomingRequests() []PairRequest {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	now := time.Now().UTC()
	res := make([]PairRequest, 0, len(pm.incomingRequests))
	for _, req := range pm.incomingRequests {
		if req.Status == "pending" && !now.After(req.ExpiresAt) {
			res = append(res, *req)
		}
	}
	return res
}

// AcceptIncomingRequest approves a pending pairing request and creates an active PairingSession.
func (pm *PairManager) AcceptIncomingRequest(reqID string) (*PairRequest, *PairingSession, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	req, ok := pm.incomingRequests[reqID]
	if !ok {
		return nil, nil, errors.New("request not found")
	}
	if time.Now().UTC().After(req.ExpiresAt) {
		req.Status = "expired"
		return nil, nil, errors.New("pairing request has expired")
	}

	req.Status = "accepted"

	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return nil, nil, fmt.Errorf("failed to generate PIN: %w", err)
	}
	secret := fmt.Sprintf("%06d", n.Int64())
	expiresAt := time.Now().UTC().Add(PairingSessionTTL)

	pm.activeSession = &PairSessionData{
		Secret:          secret,
		ExpiresAt:       expiresAt,
		AssociatedReqID: reqID,
	}

	session := &PairingSession{
		Secret:    secret,
		ExpiresAt: expiresAt,
	}

	reqCopy := *req
	return &reqCopy, session, nil
}

// DeclineIncomingRequest rejects a pending request.
func (pm *PairManager) DeclineIncomingRequest(reqID string) (*PairRequest, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	req, ok := pm.incomingRequests[reqID]
	if !ok {
		return nil, errors.New("request not found")
	}
	req.Status = "declined"
	reqCopy := *req
	return &reqCopy, nil
}

// CancelIncomingRequest marks a request as cancelled by the initiator.
func (pm *PairManager) CancelIncomingRequest(reqID string) (*PairRequest, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	req, ok := pm.incomingRequests[reqID]
	if !ok {
		return nil, errors.New("request not found")
	}
	req.Status = "cancelled"
	reqCopy := *req
	return &reqCopy, nil
}

// UpdatePeerEndpoint updates the network host, port, and TLS setting for a paired peer.
func (pm *PairManager) UpdatePeerEndpoint(peerID, host string, port int, isTLS bool) bool {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	for _, p := range pm.peers {
		if p.ID == peerID {
			changed := p.Host != host || p.Port != port || p.IsTLS != isTLS
			p.Host = host
			p.Port = port
			p.IsTLS = isTLS
			p.LastSeenAt = time.Now().UTC()
			if changed {
				_ = pm.saveToDiskLocked()
			}
			return true
		}
	}
	return false
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
