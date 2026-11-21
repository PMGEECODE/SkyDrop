package presence

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"skydrop/daemon/internal/discovery"
	"skydrop/daemon/internal/security"
)

const (
	DefaultForegroundInterval    = 5 * time.Second
	DefaultBackgroundInterval    = 30 * time.Second
	DefaultProbeTimeout          = 2500 * time.Millisecond
	MaxBackoffInterval           = 30 * time.Second
	InitialBackoffInterval       = 1 * time.Second
	MissedBeatsDegradedThreshold = 2
	MissedBeatsOfflineThreshold  = 3
)

// Service manages active peer presence, authenticated heartbeats, and live state transitions.
type Service struct {
	mu             sync.RWMutex
	pairManager    *security.PairManager
	resolver       *discovery.PeerResolver
	peers          map[string]*PeerPresence // keyed by Peer ID
	backoffs       map[string]time.Duration // current retry backoff per peer
	listeners      map[chan PeerPresence]struct{}
	httpClient     *http.Client
	stopChan       chan struct{}
	interval       time.Duration
	localDeviceID  string
	localName      string
	isTransferring func(peerID string) bool
}

// NewService creates a presence tracking engine.
func NewService(
	pm *security.PairManager,
	resolver *discovery.PeerResolver,
	localDeviceID, localName string,
) *Service {
	// Custom transport with connection pooling / keep-alives to avoid TLS handshakes on every beat
	transport := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true, // SkyDrop uses pinned certificate fingerprints
		},
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 10,
		IdleConnTimeout:     90 * time.Second,
		DisableKeepAlives:   false,
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   DefaultProbeTimeout,
	}

	return &Service{
		pairManager:   pm,
		resolver:      resolver,
		peers:         make(map[string]*PeerPresence),
		backoffs:      make(map[string]time.Duration),
		listeners:     make(map[chan PeerPresence]struct{}),
		httpClient:    client,
		stopChan:      make(chan struct{}),
		interval:      DefaultForegroundInterval,
		localDeviceID: localDeviceID,
		localName:     localName,
	}
}

// SetTransferringCheck sets a callback to check if a transfer is active for a peer.
func (s *Service) SetTransferringCheck(fn func(peerID string) bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.isTransferring = fn
}

// Start begins the presence heartbeat loop.
func (s *Service) Start() {
	go s.runLoop()
}

// Stop shuts down the presence loop.
func (s *Service) Stop() {
	select {
	case <-s.stopChan:
		return
	default:
		close(s.stopChan)
	}
}

// Subscribe returns a channel that receives updates on any presence change.
func (s *Service) Subscribe() (chan PeerPresence, func()) {
	ch := make(chan PeerPresence, 50)
	s.mu.Lock()
	s.listeners[ch] = struct{}{}
	s.mu.Unlock()

	unsubscribe := func() {
		s.mu.Lock()
		delete(s.listeners, ch)
		close(ch)
		s.mu.Unlock()
	}
	return ch, unsubscribe
}

// GetAllPresence returns a snapshot of all tracked peer presences.
func (s *Service) GetAllPresence() []PeerPresence {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]PeerPresence, 0, len(s.peers))
	for _, p := range s.peers {
		result = append(result, *p)
	}
	return result
}

// GetPresence returns presence for a specific peer ID.
func (s *Service) GetPresence(peerID string) (PeerPresence, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.peers[peerID]
	if !ok {
		return PeerPresence{}, false
	}
	return *p, true
}

// RecordActivity notes transfer activity, resetting missed beats and maintaining liveness.
func (s *Service) RecordActivity(peerID string) {
	s.mu.Lock()
	p, exists := s.peers[peerID]
	now := time.Now().UTC()
	isPaired := false
	if s.pairManager != nil && s.pairManager.GetPeerByID(peerID) != nil {
		isPaired = true
	}
	if !exists {
		p = &PeerPresence{
			PeerID:        peerID,
			State:         StateOnline,
			LastSeenAt:    now,
			LastCheckedAt: now,
			IsPaired:      isPaired,
		}
		s.peers[peerID] = p
	} else {
		p.LastSeenAt = now
		p.LastCheckedAt = now
		p.MissedBeats = 0
		p.IsPaired = isPaired
		if p.State != StateBusy {
			p.State = StateOnline
		}
		p.ErrorCode = ""
		p.ErrorMessage = ""
	}
	copyP := *p
	s.mu.Unlock()
	s.notifyListeners(copyP)
}

// TriggerImmediateCheck triggers an immediate health check across all known peers.
func (s *Service) TriggerImmediateCheck() {
	go s.checkAllPeers()
}

// HandlePeerLeave processes a graceful departure notification from a peer.
func (s *Service) HandlePeerLeave(peerID, reason string) {
	s.mu.Lock()
	p, exists := s.peers[peerID]
	if !exists {
		s.mu.Unlock()
		return
	}
	p.State = StateOffline
	p.ErrorMessage = reason
	p.LastCheckedAt = time.Now().UTC()
	copyP := *p
	s.mu.Unlock()
	log.Printf("[presence] Peer %s left gracefully: %s", peerID, reason)
	s.notifyListeners(copyP)
}

func (s *Service) runLoop() {
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()

	// Initial check on start
	s.checkAllPeers()

	for {
		select {
		case <-s.stopChan:
			return
		case <-ticker.C:
			// Add random jitter (±300ms) to avoid network heartbeat storms
			jitter := time.Duration(rand.Intn(600)-300) * time.Millisecond
			if jitter > 0 {
				time.Sleep(jitter)
			}
			s.checkAllPeers()
		}
	}
}

func (s *Service) checkAllPeers() {
	if s.resolver != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 1200*time.Millisecond)
		_, _ = s.resolver.DiscoverPeers(ctx, 800*time.Millisecond)
		cancel()
	}

	paired := s.pairManager.ListPeers()
	cachedDiscovered := s.resolver.GetCachedPeers()

	s.mu.Lock()
	// Ensure all paired peers are tracked
	for _, p := range paired {
		if existing, exists := s.peers[p.ID]; !exists {
			s.peers[p.ID] = &PeerPresence{
				PeerID:         p.ID,
				DeviceName:     p.DeviceName,
				Host:           p.Host,
				Port:           p.Port,
				IsTLS:          p.IsTLS,
				State:          StateUnknown,
				IsPaired:       true,
				LastSeenAt:     p.LastSeenAt,
				ConnectionType: "wifi",
			}
		} else {
			if existing.Host == "" && p.Host != "" {
				existing.Host = p.Host
				existing.Port = p.Port
				existing.IsTLS = p.IsTLS
			}
		}
	}
	peersToCheck := make([]*PeerPresence, 0, len(s.peers))
	for _, p := range s.peers {
		peersToCheck = append(peersToCheck, p)
	}
	s.mu.Unlock()

	var wg sync.WaitGroup
	for _, p := range peersToCheck {
		wg.Add(1)
		go func(peer *PeerPresence) {
			defer wg.Done()
			s.probePeer(peer, paired, cachedDiscovered)
		}(p)
	}
	wg.Wait()
}

func (s *Service) probePeer(
	peer *PeerPresence,
	paired []*security.PairedPeer,
	discovered []discovery.DiscoveredPeer,
) {
	// If transfer is currently busy with this peer, count progress as liveness
	if s.isTransferring != nil && s.isTransferring(peer.PeerID) {
		s.mu.Lock()
		peer.State = StateBusy
		peer.MissedBeats = 0
		peer.LastSeenAt = time.Now().UTC()
		peer.LastCheckedAt = time.Now().UTC()
		copyP := *peer
		s.mu.Unlock()
		s.notifyListeners(copyP)
		return
	}

	// Resolve target endpoint
	host, port, isTLS, token, fp := s.resolveEndpoint(peer, paired, discovered)
	if host == "" || port <= 0 {
		s.markMissedBeat(peer, ErrCodeUnreachable, "No reachable IP address for peer")
		return
	}

	scheme := "http"
	if isTLS {
		scheme = "https"
	}
	healthURL := fmt.Sprintf("%s://%s:%d/health", scheme, host, port)

	req, err := http.NewRequestWithContext(context.Background(), http.MethodGet, healthURL, nil)
	if err != nil {
		s.markMissedBeat(peer, ErrCodeUnreachable, err.Error())
		return
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("X-SkyDrop-Device-ID", s.localDeviceID)
	req.Header.Set("X-SkyDrop-Version", AppVersion)

	start := time.Now()
	resp, err := s.httpClient.Do(req)
	latency := time.Since(start).Milliseconds()

	// If HTTPS failed due to TLS error / connection reset / EOF, try HTTP fallback
	if err != nil && isTLS {
		httpHealthURL := fmt.Sprintf("http://%s:%d/health", host, port)
		httpReq, httpErr := http.NewRequestWithContext(context.Background(), http.MethodGet, httpHealthURL, nil)
		if httpErr == nil {
			if token != "" {
				httpReq.Header.Set("Authorization", "Bearer "+token)
			}
			httpReq.Header.Set("X-SkyDrop-Device-ID", s.localDeviceID)
			httpReq.Header.Set("X-SkyDrop-Version", AppVersion)
			if httpResp, fallbackErr := s.httpClient.Do(httpReq); fallbackErr == nil {
				resp = httpResp
				err = nil
				isTLS = false // Successfully connected via HTTP
				latency = time.Since(start).Milliseconds()
			}
		}
	}

	if err != nil {
		code := ErrCodeUnreachable
		if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
			code = ErrCodeTimeout
		} else if strings.Contains(err.Error(), "connection refused") || strings.Contains(err.Error(), "reset by peer") {
			code = ErrCodeConnReset
		}
		s.markMissedBeat(peer, code, err.Error())
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		s.mu.Lock()
		peer.State = StateError
		peer.ErrorCode = ErrCodeAuthFailed
		peer.ErrorMessage = "Authentication failed or pairing revoked by remote device"
		peer.LastCheckedAt = time.Now().UTC()
		copyP := *peer
		s.mu.Unlock()
		s.notifyListeners(copyP)
		return
	}

	if resp.StatusCode != http.StatusOK {
		s.markMissedBeat(peer, ErrCodeUnknown, fmt.Sprintf("Unexpected HTTP status: %d", resp.StatusCode))
		return
	}

	var health HealthResponse
	if err := json.NewDecoder(resp.Body).Decode(&health); err != nil {
		s.markMissedBeat(peer, ErrCodeVersionMismatch, "Invalid health JSON response")
		return
	}

	// Check certificate fingerprint if pinned
	if fp != "" && health.Fingerprint != "" && !strings.EqualFold(fp, health.Fingerprint) {
		s.mu.Lock()
		peer.State = StateError
		peer.ErrorCode = ErrCodeCertMismatch
		peer.ErrorMessage = "Remote certificate fingerprint mismatch"
		peer.LastCheckedAt = time.Now().UTC()
		copyP := *peer
		s.mu.Unlock()
		s.notifyListeners(copyP)
		return
	}

	s.mu.Lock()
	now := time.Now().UTC()
	peer.State = StateOnline
	if health.State == "transferring" {
		peer.State = StateBusy
	}
	peer.ErrorCode = ""
	peer.ErrorMessage = ""
	peer.LastSeenAt = now
	peer.LastCheckedAt = now
	peer.LatencyMs = latency
	peer.MissedBeats = 0
	peer.Host = host
	peer.Port = port
	peer.IsTLS = isTLS
	peer.Capabilities = health.Capabilities
	peer.AppVersion = health.AppVersion
	peer.ProtocolVersion = health.ProtocolVersion
	if health.DeviceName != "" {
		peer.DeviceName = health.DeviceName
	}
	peer.IsPaired = token != ""
	s.backoffs[peer.PeerID] = InitialBackoffInterval
	copyP := *peer
	s.mu.Unlock()

	s.notifyListeners(copyP)
}

func (s *Service) markMissedBeat(peer *PeerPresence, errCode, errMsg string) {
	s.mu.Lock()
	peer.MissedBeats++
	peer.LastCheckedAt = time.Now().UTC()
	peer.ErrorCode = errCode
	peer.ErrorMessage = errMsg

	// Exponential backoff increment for retries
	currBackoff := s.backoffs[peer.PeerID]
	if currBackoff == 0 {
		currBackoff = InitialBackoffInterval
	} else {
		currBackoff *= 2
		if currBackoff > MaxBackoffInterval {
			currBackoff = MaxBackoffInterval
		}
	}
	s.backoffs[peer.PeerID] = currBackoff

	if peer.MissedBeats >= MissedBeatsOfflineThreshold {
		peer.State = StateOffline
	} else if peer.MissedBeats == MissedBeatsDegradedThreshold {
		peer.State = StateDegraded
	} else if peer.State == StateUnknown {
		peer.State = StateChecking
	}

	copyP := *peer
	s.mu.Unlock()

	s.notifyListeners(copyP)
}

func (s *Service) resolveEndpoint(
	peer *PeerPresence,
	paired []*security.PairedPeer,
	discovered []discovery.DiscoveredPeer,
) (host string, port int, isTLS bool, token string, fingerprint string) {
	normPeerName := security.NormalizeDeviceName(peer.DeviceName)

	var pairedHost string
	var pairedPort int
	var pairedTLS bool

	// 1. Look for paired credentials
	for _, p := range paired {
		if p.ID == peer.PeerID || (normPeerName != "" && security.NormalizeDeviceName(p.DeviceName) == normPeerName) {
			token = p.AuthToken
			fingerprint = p.Fingerprint
			pairedHost = p.Host
			pairedPort = p.Port
			pairedTLS = p.IsTLS
			break
		}
	}

	// 2. Check fresh mDNS resolution
	for _, d := range discovered {
		normDiscoveredName := security.NormalizeDeviceName(d.Name)
		if (fingerprint != "" && strings.EqualFold(d.Fingerprint, fingerprint)) ||
			(normPeerName != "" && normDiscoveredName == normPeerName) ||
			d.ID == peer.PeerID ||
			(peer.Host != "" && d.Host == peer.Host) {
			return d.Host, d.Port, d.IsTLS, token, fingerprint
		}
	}

	// 3. Fallback to cached peer or paired peer host:port
	if peer.Host != "" && peer.Port > 0 {
		return peer.Host, peer.Port, peer.IsTLS, token, fingerprint
	}
	if pairedHost != "" && pairedPort > 0 {
		return pairedHost, pairedPort, pairedTLS, token, fingerprint
	}

	return "", 0, true, token, fingerprint
}

func (s *Service) notifyListeners(p PeerPresence) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for ch := range s.listeners {
		select {
		case ch <- p:
		default:
			// Non-blocking drop if consumer buffer is full
		}
	}
}
