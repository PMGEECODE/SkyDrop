package discovery

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/grandcat/zeroconf"
)

// DiscoveredPeer represents an active peer discovered via mDNS on the local network.
type DiscoveredPeer struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Host        string    `json:"host"`
	Port        int       `json:"port"`
	IsTLS       bool      `json:"is_tls"`
	Fingerprint string    `json:"fingerprint,omitempty"`
	OS          string    `json:"os,omitempty"`
	LastSeen    time.Time `json:"last_seen"`
}

// BaseURL returns the HTTP or HTTPS base URL for communicating with this peer.
func (p DiscoveredPeer) BaseURL() string {
	scheme := "http"
	if p.IsTLS {
		scheme = "https"
	}
	return fmt.Sprintf("%s://%s:%d", scheme, p.Host, p.Port)
}

// PeerResolver discovers and maintains a cache of active peers on the local network.
type PeerResolver struct {
	mu                sync.RWMutex
	peers             map[string]DiscoveredPeer
	ignoreName        string
	ignorePort        int
	ignoreFingerprint string
}

// NewPeerResolver initializes a new PeerResolver instance.
func NewPeerResolver() *PeerResolver {
	return &PeerResolver{
		peers: make(map[string]DiscoveredPeer),
	}
}

// SetSelf configures local device identity to filter out the local machine from discovery results.
func (pr *PeerResolver) SetSelf(name string, port int, fingerprint string) {
	pr.mu.Lock()
	defer pr.mu.Unlock()
	pr.ignoreName = strings.TrimSpace(name)
	pr.ignorePort = port
	pr.ignoreFingerprint = strings.TrimSpace(fingerprint)
}

// IsSelf reports whether the given peer represents the local machine.
func (pr *PeerResolver) IsSelf(p DiscoveredPeer) bool {
	pr.mu.RLock()
	defer pr.mu.RUnlock()
	return pr.isSelfLocked(p)
}

// IsLocalAddress checks if the given host string is loopback or belongs to a local network interface.
func IsLocalAddress(host string) bool {
	return isLocalAddress(host)
}

func isLocalAddress(host string) bool {
	if host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "" {
		return true
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return false
	}
	for _, iface := range ifaces {
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip != nil && ip.String() == host {
				return true
			}
		}
	}
	return false
}

// DiscoverPeers searches for peers advertising _filedrop._tcp and _skydrop._tcp for the given duration.
func (pr *PeerResolver) DiscoverPeers(ctx context.Context, timeout time.Duration) ([]DiscoveredPeer, error) {
	resolver, err := zeroconf.NewResolver(nil)
	if err != nil {
		return nil, fmt.Errorf("failed to initialize zeroconf resolver: %w", err)
	}

	searchCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	services := []string{"_filedrop._tcp", "_skydrop._tcp"}
	merged := make(chan *zeroconf.ServiceEntry, 32)
	var wg sync.WaitGroup

	for _, svc := range services {
		ch := make(chan *zeroconf.ServiceEntry, 16)
		if err := resolver.Browse(searchCtx, svc, "local.", ch); err == nil {
			wg.Add(1)
			go func(c <-chan *zeroconf.ServiceEntry) {
				defer wg.Done()
				for e := range c {
					merged <- e
				}
			}(ch)
		}
	}

	go func() {
		wg.Wait()
		close(merged)
	}()

	for entry := range merged {
		var host string
		if len(entry.AddrIPv4) > 0 {
			host = entry.AddrIPv4[0].String()
		} else if len(entry.AddrIPv6) > 0 {
			host = entry.AddrIPv6[0].String()
		} else {
			host = strings.TrimSuffix(entry.HostName, ".")
		}

		isTLS := true
		var fingerprint string
		var peerOS string
		var deviceID string

		for _, txt := range entry.Text {
			parts := strings.SplitN(txt, "=", 2)
			if len(parts) == 2 {
				switch strings.ToLower(parts[0]) {
				case "tls":
					isTLS = parts[1] == "true" || parts[1] == "1"
				case "fingerprint":
					fingerprint = parts[1]
				case "os":
					peerOS = parts[1]
				case "device_id", "id":
					deviceID = parts[1]
				}
			}
		}

		peerID := fmt.Sprintf("%s:%d", host, entry.Port)
		if deviceID != "" {
			peerID = deviceID
		}
		peer := DiscoveredPeer{
			ID:          peerID,
			Name:        entry.Instance,
			Host:        host,
			Port:        entry.Port,
			IsTLS:       isTLS,
			Fingerprint: fingerprint,
			OS:          peerOS,
			LastSeen:    time.Now().UTC(),
		}

		if pr.IsSelf(peer) {
			continue
		}

		pr.mu.Lock()
		pr.peers[peerID] = peer
		pr.mu.Unlock()
	}

	return pr.GetCachedPeers(), nil
}

// AddCachedPeer manually registers or updates a peer in the cache (useful for direct peer entry or tests).
func (pr *PeerResolver) AddCachedPeer(peer DiscoveredPeer) {
	pr.mu.Lock()
	defer pr.mu.Unlock()
	if peer.ID == "" {
		peer.ID = fmt.Sprintf("%s:%d", peer.Host, peer.Port)
	}
	pr.peers[peer.ID] = peer
}

// GetCachedPeers returns a snapshot of currently cached peers.
func (pr *PeerResolver) GetCachedPeers() []DiscoveredPeer {
	pr.mu.RLock()
	defer pr.mu.RUnlock()

	result := make([]DiscoveredPeer, 0, len(pr.peers))
	for _, p := range pr.peers {
		if !pr.isSelfLocked(p) {
			result = append(result, p)
		}
	}
	return result
}

func (pr *PeerResolver) isSelfLocked(p DiscoveredPeer) bool {
	if pr.ignoreFingerprint != "" && p.Fingerprint != "" && strings.EqualFold(p.Fingerprint, pr.ignoreFingerprint) {
		return true
	}
	if pr.ignoreName != "" && strings.EqualFold(p.Name, pr.ignoreName) {
		return true
	}
	if pr.ignorePort > 0 && p.Port == pr.ignorePort && isLocalAddress(p.Host) {
		return true
	}
	return false
}
