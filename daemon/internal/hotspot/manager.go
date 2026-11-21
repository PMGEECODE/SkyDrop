package hotspot

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/skip2/go-qrcode"
)

// Standard errors
var (
	ErrNetworkManagerMissing = errors.New("NetworkManager (nmcli) is not installed or available in PATH")
	ErrNetworkManagerNotRunning = errors.New("NetworkManager service is not running")
	ErrNoWifiDevice          = errors.New("no Wi-Fi adapter found on this system")
	ErrNoAPSupport           = errors.New("Wi-Fi adapter does not support Access Point (AP) mode")
	ErrNeedsDisconnect       = errors.New("Wi-Fi adapter cannot host a hotspot while connected to Wi-Fi")
	ErrHotspotAlreadyActive  = errors.New("hotspot is already active")
	ErrHotspotNotActive      = errors.New("hotspot is not active")
)

// CmdRunner abstracts shell command execution for testability.
type CmdRunner interface {
	Run(ctx context.Context, name string, args ...string) ([]byte, error)
	LookPath(file string) (string, error)
}

// DefaultCmdRunner executes real system commands.
type DefaultCmdRunner struct{}

func (d *DefaultCmdRunner) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	return cmd.CombinedOutput()
}

func (d *DefaultCmdRunner) LookPath(file string) (string, error) {
	return exec.LookPath(file)
}

// ClientInfo represents a connected Wi-Fi station.
type ClientInfo struct {
	MAC    string `json:"mac"`
	IP     string `json:"ip,omitempty"`
	Signal string `json:"signal,omitempty"`
}

// Capabilities describes the host's Wi-Fi and NetworkManager state.
type Capabilities struct {
	Supported            bool   `json:"supported"`
	HasNetworkManager    bool   `json:"has_network_manager"`
	HasWifiDevice        bool   `json:"has_wifi_device"`
	Device               string `json:"device,omitempty"`
	SupportsAP           bool   `json:"supports_ap"`
	SupportsConcurrentAP bool   `json:"supports_concurrent_ap"`
	IsConnected          bool   `json:"is_connected"`
	ActiveConnectionName string `json:"active_connection_name,omitempty"`
	ErrorMessage         string `json:"error_message,omitempty"`
}

// Status represents the current state of the SkyDrop hotspot.
type Status struct {
	Active             bool         `json:"active"`
	SSID               string       `json:"ssid,omitempty"`
	Password           string       `json:"password,omitempty"`
	IP                 string       `json:"ip,omitempty"`
	Interface          string       `json:"interface,omitempty"`
	ConnectedClients   []ClientInfo `json:"connected_clients"`
	IdleTimeoutMinutes int          `json:"idle_timeout_minutes,omitempty"`
	PreviousConnection string       `json:"previous_connection,omitempty"`
	QRCode             string       `json:"qr_code,omitempty"`
	StartedAt          int64        `json:"started_at,omitempty"`
	Error              string       `json:"error,omitempty"`
}

// StartOptions configures the hotspot launch.
type StartOptions struct {
	DisconnectCurrent  bool `json:"disconnect_current"`
	IdleTimeoutMinutes int  `json:"idle_timeout_minutes"`
}

// Manager controls the Linux NetworkManager Wi-Fi hotspot.
type Manager struct {
	mu                 sync.RWMutex
	runner             CmdRunner
	active             bool
	ssid               string
	password           string
	ip                 string
	iface              string
	qrCode             string
	startedAt          int64
	previousConnection string
	idleTimeoutMinutes int
	clients            []ClientInfo
	stopCh             chan struct{}
	lastActiveWithPeer time.Time
}

// NewManager creates a new hotspot Manager instance.
func NewManager(runner CmdRunner) *Manager {
	if runner == nil {
		runner = &DefaultCmdRunner{}
	}
	return &Manager{
		runner: runner,
	}
}

// CleanupLeftovers removes any lingering skydrop-hotspot profiles left by crashes.
func (m *Manager) CleanupLeftovers(ctx context.Context) {
	out, err := m.runner.Run(ctx, "nmcli", "-t", "-f", "NAME,UUID", "connection", "show")
	if err != nil {
		return
	}

	lines := strings.Split(string(out), "\n")
	for _, line := range lines {
		parts := strings.Split(line, ":")
		if len(parts) >= 1 {
			name := strings.TrimSpace(parts[0])
			if name == "skydrop-hotspot" || strings.HasPrefix(name, "SkyDrop-Hotspot") {
				_, _ = m.runner.Run(ctx, "nmcli", "connection", "delete", name)
			}
		}
	}
}

// CheckCapabilities inspects the system to see if an AP hotspot can be hosted.
func (m *Manager) CheckCapabilities(ctx context.Context) (*Capabilities, error) {
	caps := &Capabilities{}

	// 1. Check nmcli availability
	if _, err := m.runner.LookPath("nmcli"); err != nil {
		caps.ErrorMessage = ErrNetworkManagerMissing.Error()
		return caps, ErrNetworkManagerMissing
	}
	caps.HasNetworkManager = true

	// Check if NetworkManager is running
	nmStatus, err := m.runner.Run(ctx, "nmcli", "general", "status")
	if err != nil || strings.Contains(string(nmStatus), "Error: NetworkManager is not running") {
		caps.ErrorMessage = ErrNetworkManagerNotRunning.Error()
		return caps, ErrNetworkManagerNotRunning
	}

	// 2. Find Wi-Fi device
	devOut, err := m.runner.Run(ctx, "nmcli", "-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device")
	if err != nil {
		caps.ErrorMessage = "Failed to query network devices"
		return caps, err
	}

	lines := strings.Split(string(devOut), "\n")
	var wifiDev string
	var isConn bool
	var activeConn string

	for _, line := range lines {
		parts := strings.Split(line, ":")
		if len(parts) >= 2 && parts[1] == "wifi" {
			wifiDev = parts[0]
			if len(parts) >= 3 && parts[2] == "connected" {
				isConn = true
				if len(parts) >= 4 {
					activeConn = parts[3]
				}
			}
			break
		}
	}

	if wifiDev == "" {
		caps.ErrorMessage = ErrNoWifiDevice.Error()
		return caps, ErrNoWifiDevice
	}

	caps.HasWifiDevice = true
	caps.Device = wifiDev
	caps.IsConnected = isConn
	caps.ActiveConnectionName = activeConn

	// 3. Check AP mode support using iw
	if _, err := m.runner.LookPath("iw"); err == nil {
		iwOut, err := m.runner.Run(ctx, "iw", "list")
		if err == nil {
			iwStr := string(iwOut)
			caps.SupportsAP = strings.Contains(iwStr, "* AP\n") || strings.Contains(iwStr, "* AP ") || strings.Contains(iwStr, "\t * AP")

			// Check interface combinations for concurrent AP + Station support
			if strings.Contains(iwStr, "interface combinations are not supported") {
				caps.SupportsConcurrentAP = false
			} else if strings.Contains(iwStr, "valid interface combinations:") {
				caps.SupportsConcurrentAP = true
			}
		}
	} else {
		// Fallback: assume supported if iw not installed but wifi device exists
		caps.SupportsAP = true
	}

	if !caps.SupportsAP {
		caps.ErrorMessage = ErrNoAPSupport.Error()
		return caps, ErrNoAPSupport
	}

	caps.Supported = true
	return caps, nil
}

// GenerateSSID generates a clean SkyDrop SSID with 4 random uppercase chars/digits.
func GenerateSSID() string {
	const charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 4)
	for i := range b {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(charset))))
		b[i] = charset[n.Int64()]
	}
	return fmt.Sprintf("SkyDrop-%s", string(b))
}

// GeneratePassword creates a strong 12-char random alphanumeric password.
func GeneratePassword() string {
	const charset = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 12)
	for i := range b {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(charset))))
		b[i] = charset[n.Int64()]
	}
	return string(b)
}

// StartHotspot initiates a Wi-Fi hotspot through NetworkManager.
func (m *Manager) StartHotspot(ctx context.Context, opts StartOptions) (*Status, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.active {
		return m.currentStatusLocked(), nil
	}

	caps, err := m.CheckCapabilities(ctx)
	if err != nil {
		return nil, err
	}

	// Handle connected Wi-Fi adapter that cannot do concurrent AP+STA
	var previousConn string
	if caps.IsConnected && !caps.SupportsConcurrentAP {
		if !opts.DisconnectCurrent {
			return nil, fmt.Errorf("%w: connected to '%s'", ErrNeedsDisconnect, caps.ActiveConnectionName)
		}
		previousConn = caps.ActiveConnectionName
	}

	// Clean up any leftover profile first
	_, _ = m.runner.Run(ctx, "nmcli", "connection", "delete", "skydrop-hotspot")

	ssid := GenerateSSID()
	password := GeneratePassword()

	// Create hotspot using nmcli
	out, err := m.runner.Run(ctx, "nmcli", "device", "wifi", "hotspot",
		"ifname", caps.Device,
		"con-name", "skydrop-hotspot",
		"ssid", ssid,
		"password", password,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create hotspot: %s (%w)", strings.TrimSpace(string(out)), err)
	}

	// Give NetworkManager a moment to assign the shared IP
	time.Sleep(500 * time.Millisecond)

	ipAddr := m.detectGatewayIP(ctx, caps.Device)
	if ipAddr == "" {
		ipAddr = "10.42.0.1" // standard NetworkManager shared IPv4 default
	}

	// Generate standard Wi-Fi QR Code: WIFI:T:WPA;S:<ssid>;P:<password>;;
	qrContent := fmt.Sprintf("WIFI:T:WPA;S:%s;P:%s;;", ssid, password)
	var qrDataURL string
	if pngBytes, qrErr := qrcode.Encode(qrContent, qrcode.Medium, 256); qrErr == nil {
		qrDataURL = "data:image/png;base64," + base64.StdEncoding.EncodeToString(pngBytes)
	}

	m.active = true
	m.ssid = ssid
	m.password = password
	m.ip = ipAddr
	m.iface = caps.Device
	m.qrCode = qrDataURL
	m.startedAt = time.Now().Unix()
	m.previousConnection = previousConn
	m.idleTimeoutMinutes = opts.IdleTimeoutMinutes
	m.clients = make([]ClientInfo, 0)
	m.lastActiveWithPeer = time.Now()
	m.stopCh = make(chan struct{})

	// Start background monitors
	go m.monitorClients(m.stopCh, caps.Device, opts.IdleTimeoutMinutes)

	return m.currentStatusLocked(), nil
}

// detectGatewayIP resolves the IP address assigned to the hotspot interface.
func (m *Manager) detectGatewayIP(ctx context.Context, iface string) string {
	// First check ip addr show
	out, err := m.runner.Run(ctx, "ip", "-4", "-o", "addr", "show", "dev", iface)
	if err == nil {
		fields := strings.Fields(string(out))
		for i, field := range fields {
			if field == "inet" && i+1 < len(fields) {
				cidr := fields[i+1]
				ip, _, err := net.ParseCIDR(cidr)
				if err == nil {
					return ip.String()
				}
			}
		}
	}

	// Fallback check nmcli
	out, err = m.runner.Run(ctx, "nmcli", "-g", "IP4.ADDRESS", "connection", "show", "skydrop-hotspot")
	if err == nil {
		line := strings.TrimSpace(string(out))
		if line != "" {
			parts := strings.Split(line, "/")
			if len(parts) > 0 && net.ParseIP(parts[0]) != nil {
				return parts[0]
			}
		}
	}

	return ""
}

// monitorClients periodically queries connected stations and handles idle timeout.
func (m *Manager) monitorClients(stopCh <-chan struct{}, iface string, idleTimeoutMinutes int) {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-stopCh:
			return
		case <-ticker.C:
			clients := m.queryStations(context.Background(), iface)

			m.mu.Lock()
			if !m.active {
				m.mu.Unlock()
				return
			}
			m.clients = clients

			// Idle timeout check
			if len(clients) > 0 {
				m.lastActiveWithPeer = time.Now()
			} else if idleTimeoutMinutes > 0 && time.Since(m.lastActiveWithPeer) > time.Duration(idleTimeoutMinutes)*time.Minute {
				m.mu.Unlock()
				_ = m.StopHotspot(context.Background())
				return
			}
			m.mu.Unlock()
		}
	}
}

// queryStations queries connected stations via iw and ip neigh.
func (m *Manager) queryStations(ctx context.Context, iface string) []ClientInfo {
	clients := make([]ClientInfo, 0)
	macToIP := make(map[string]string)

	// Query ARP/Neigh table for MAC -> IP mappings
	neighOut, err := m.runner.Run(ctx, "ip", "-4", "neigh", "show", "dev", iface)
	if err == nil {
		lines := strings.Split(string(neighOut), "\n")
		for _, line := range lines {
			parts := strings.Fields(line)
			if len(parts) >= 2 {
				for i, p := range parts {
					if p == "lladdr" && i+1 < len(parts) {
						mac := strings.ToLower(parts[i+1])
						macToIP[mac] = parts[0]
						break
					}
				}
			}
		}
	}

	// Query iw station dump
	iwOut, err := m.runner.Run(ctx, "iw", "dev", iface, "station", "dump")
	if err == nil {
		lines := strings.Split(string(iwOut), "\n")
		var curMAC string
		var curSignal string

		for _, line := range lines {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "Station ") {
				if curMAC != "" {
					clients = append(clients, ClientInfo{
						MAC:    curMAC,
						IP:     macToIP[strings.ToLower(curMAC)],
						Signal: curSignal,
					})
				}
				fields := strings.Fields(line)
				if len(fields) >= 2 {
					curMAC = fields[1]
					curSignal = ""
				}
			} else if strings.HasPrefix(line, "signal:") {
				curSignal = strings.TrimPrefix(line, "signal:")
				curSignal = strings.TrimSpace(curSignal)
			}
		}

		if curMAC != "" {
			clients = append(clients, ClientInfo{
				MAC:    curMAC,
				IP:     macToIP[strings.ToLower(curMAC)],
				Signal: curSignal,
			})
		}
	}

	return clients
}

// StopHotspot tears down the hotspot and restores any previous connection.
func (m *Manager) StopHotspot(ctx context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.active {
		return nil
	}

	if m.stopCh != nil {
		close(m.stopCh)
		m.stopCh = nil
	}

	// Tear down connection profile
	_, _ = m.runner.Run(ctx, "nmcli", "connection", "down", "skydrop-hotspot")
	_, _ = m.runner.Run(ctx, "nmcli", "connection", "delete", "skydrop-hotspot")

	// Restore previously disconnected connection if applicable
	if m.previousConnection != "" {
		_, _ = m.runner.Run(ctx, "nmcli", "connection", "up", "id", m.previousConnection)
	}

	m.active = false
	m.ssid = ""
	m.password = ""
	m.ip = ""
	m.iface = ""
	m.qrCode = ""
	m.previousConnection = ""
	m.clients = make([]ClientInfo, 0)

	return nil
}

// GetStatus returns the current live status of the hotspot.
func (m *Manager) GetStatus() *Status {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.currentStatusLocked()
}

func (m *Manager) currentStatusLocked() *Status {
	clientsCopy := make([]ClientInfo, len(m.clients))
	copy(clientsCopy, m.clients)

	return &Status{
		Active:             m.active,
		SSID:               m.ssid,
		Password:           m.password,
		IP:                 m.ip,
		Interface:          m.iface,
		ConnectedClients:   clientsCopy,
		IdleTimeoutMinutes: m.idleTimeoutMinutes,
		PreviousConnection: m.previousConnection,
		QRCode:             m.qrCode,
		StartedAt:          m.startedAt,
	}
}
