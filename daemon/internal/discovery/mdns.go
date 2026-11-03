package discovery

import (
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/grandcat/zeroconf"
)

// Advertiser manages mDNS service advertisement over the local network.
type Advertiser struct {
	mu     sync.Mutex
	server *zeroconf.Server
}

// ServiceConfig defines parameters for mDNS broadcast.
type ServiceConfig struct {
	InstanceName string
	ServiceName  string // e.g. "_filedrop._tcp"
	Domain       string // e.g. "local."
	Port         int
	TextRecords  []string
}

// NewAdvertiser initializes a new mDNS advertiser.
func NewAdvertiser() *Advertiser {
	return &Advertiser{}
}

// Register registers and starts advertising the mDNS service on local network interfaces.
func (a *Advertiser) Register(cfg ServiceConfig) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.server != nil {
		return errors.New("mDNS advertiser is already running")
	}

	if cfg.InstanceName == "" {
		return errors.New("instance name cannot be empty")
	}
	if cfg.Port < 1 || cfg.Port > 65535 {
		return fmt.Errorf("invalid port: %d", cfg.Port)
	}

	service := cfg.ServiceName
	if service == "" {
		service = "_filedrop._tcp"
	}

	// Normalize domain
	domain := cfg.Domain
	if domain == "" {
		domain = "local."
	}
	if !strings.HasSuffix(domain, ".") {
		domain += "."
	}

	srv, err := zeroconf.Register(
		cfg.InstanceName,
		service,
		domain,
		cfg.Port,
		cfg.TextRecords,
		nil, // all network interfaces
	)
	if err != nil {
		return fmt.Errorf("failed to register mDNS service: %w", err)
	}

	a.server = srv
	return nil
}

// Shutdown gracefully unregisters the mDNS service from the local network.
func (a *Advertiser) Shutdown() {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.server != nil {
		a.server.Shutdown()
		a.server = nil
	}
}
