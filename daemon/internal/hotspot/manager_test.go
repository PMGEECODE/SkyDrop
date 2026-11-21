package hotspot

import (
	"context"
	"errors"
	"strings"
	"testing"
)

// mockRunner implements CmdRunner for unit testing.
type mockRunner struct {
	lookPathFunc func(file string) (string, error)
	runFunc      func(ctx context.Context, name string, args ...string) ([]byte, error)
	calls        [][]string
}

func (m *mockRunner) LookPath(file string) (string, error) {
	if m.lookPathFunc != nil {
		return m.lookPathFunc(file)
	}
	return "/usr/bin/" + file, nil
}

func (m *mockRunner) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmdRecord := append([]string{name}, args...)
	m.calls = append(m.calls, cmdRecord)

	if m.runFunc != nil {
		return m.runFunc(ctx, name, args...)
	}
	return []byte(""), nil
}

func TestGenerateSSID(t *testing.T) {
	ssid1 := GenerateSSID()
	ssid2 := GenerateSSID()

	if !strings.HasPrefix(ssid1, "SkyDrop-") {
		t.Fatalf("expected SSID to start with SkyDrop-, got: %s", ssid1)
	}
	if len(ssid1) != 12 { // "SkyDrop-" (8) + 4 = 12
		t.Fatalf("expected SSID length 12, got: %d (%s)", len(ssid1), ssid1)
	}
	if ssid1 == ssid2 {
		t.Fatalf("expected randomly generated SSIDs to be distinct: %s vs %s", ssid1, ssid2)
	}
}

func TestGeneratePassword(t *testing.T) {
	pw1 := GeneratePassword()
	pw2 := GeneratePassword()

	if len(pw1) != 12 {
		t.Fatalf("expected password length 12, got: %d (%s)", len(pw1), pw1)
	}
	if strings.ContainsAny(pw1, " \t\n") {
		t.Fatalf("password must not contain whitespace: %s", pw1)
	}
	if pw1 == pw2 {
		t.Fatalf("expected random passwords to be distinct: %s vs %s", pw1, pw2)
	}
}

func TestCheckCapabilities_NmcliMissing(t *testing.T) {
	runner := &mockRunner{
		lookPathFunc: func(file string) (string, error) {
			if file == "nmcli" {
				return "", errors.New("not found")
			}
			return "/usr/bin/" + file, nil
		},
	}

	mgr := NewManager(runner)
	caps, err := mgr.CheckCapabilities(context.Background())
	if !errors.Is(err, ErrNetworkManagerMissing) {
		t.Fatalf("expected ErrNetworkManagerMissing, got: %v", err)
	}
	if caps.HasNetworkManager {
		t.Fatalf("expected HasNetworkManager to be false")
	}
}

func TestCheckCapabilities_NoWifiDevice(t *testing.T) {
	runner := &mockRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("eth0:ethernet:connected:Wired connection 1\nlo:loopback:unmanaged:\n"), nil
			}
			return []byte(""), nil
		},
	}

	mgr := NewManager(runner)
	caps, err := mgr.CheckCapabilities(context.Background())
	if !errors.Is(err, ErrNoWifiDevice) {
		t.Fatalf("expected ErrNoWifiDevice, got: %v", err)
	}
	if caps.HasWifiDevice {
		t.Fatalf("expected HasWifiDevice to be false")
	}
}

func TestCheckCapabilities_NoAPSupport(t *testing.T) {
	runner := &mockRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("wlan0:wifi:connected:HomeWiFi\n"), nil
			}
			if name == "iw" && len(args) >= 1 && args[0] == "list" {
				// iw list without AP mode
				return []byte("Supported interface modes:\n\t * IBSS\n\t * managed\n\t * monitor\n"), nil
			}
			return []byte(""), nil
		},
	}

	mgr := NewManager(runner)
	caps, err := mgr.CheckCapabilities(context.Background())
	if !errors.Is(err, ErrNoAPSupport) {
		t.Fatalf("expected ErrNoAPSupport, got: %v", err)
	}
	if caps.SupportsAP {
		t.Fatalf("expected SupportsAP to be false")
	}
}

func TestCheckCapabilities_SuccessWithNonConcurrentAdapter(t *testing.T) {
	runner := &mockRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("wlan0:wifi:connected:OfficeWiFi\n"), nil
			}
			if name == "iw" && len(args) >= 1 && args[0] == "list" {
				return []byte("Supported interface modes:\n\t * IBSS\n\t * managed\n\t * AP\n\t * monitor\ninterface combinations are not supported\n"), nil
			}
			return []byte(""), nil
		},
	}

	mgr := NewManager(runner)
	caps, err := mgr.CheckCapabilities(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !caps.Supported || !caps.SupportsAP {
		t.Fatalf("expected AP supported")
	}
	if caps.SupportsConcurrentAP {
		t.Fatalf("expected SupportsConcurrentAP to be false")
	}
	if !caps.IsConnected || caps.ActiveConnectionName != "OfficeWiFi" {
		t.Fatalf("expected connected to OfficeWiFi, got: %v (%s)", caps.IsConnected, caps.ActiveConnectionName)
	}
}

func TestStartHotspot_RequiresDisconnectConfirmation(t *testing.T) {
	runner := &mockRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("wlan0:wifi:connected:MyHomeWiFi\n"), nil
			}
			if name == "iw" && len(args) >= 1 && args[0] == "list" {
				return []byte("Supported interface modes:\n\t * IBSS\n\t * managed\n\t * AP\ninterface combinations are not supported\n"), nil
			}
			return []byte(""), nil
		},
	}

	mgr := NewManager(runner)
	_, err := mgr.StartHotspot(context.Background(), StartOptions{DisconnectCurrent: false})
	if !errors.Is(err, ErrNeedsDisconnect) {
		t.Fatalf("expected ErrNeedsDisconnect, got: %v", err)
	}
}

func TestStartHotspot_SuccessAndStop(t *testing.T) {
	runner := &mockRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("wlan0:wifi:connected:MyHomeWiFi\n"), nil
			}
			if name == "iw" && len(args) >= 1 && args[0] == "list" {
				return []byte("Supported interface modes:\n\t * IBSS\n\t * managed\n\t * AP\ninterface combinations are not supported\n"), nil
			}
			if name == "ip" && len(args) >= 4 && args[0] == "-4" && args[1] == "-o" {
				return []byte("2: wlan0    inet 10.42.0.1/24 brd 10.42.0.255 scope global wlan0\n"), nil
			}
			return []byte(""), nil
		},
	}

	mgr := NewManager(runner)
	status, err := mgr.StartHotspot(context.Background(), StartOptions{
		DisconnectCurrent:  true,
		IdleTimeoutMinutes: 10,
	})
	if err != nil {
		t.Fatalf("unexpected StartHotspot error: %v", err)
	}

	if !status.Active {
		t.Fatalf("expected status to be active")
	}
	if !strings.HasPrefix(status.SSID, "SkyDrop-") {
		t.Fatalf("expected SSID starting with SkyDrop-, got: %s", status.SSID)
	}
	if len(status.Password) != 12 {
		t.Fatalf("expected 12-char password, got: %s", status.Password)
	}
	if status.IP != "10.42.0.1" {
		t.Fatalf("expected IP 10.42.0.1, got: %s", status.IP)
	}
	if status.PreviousConnection != "MyHomeWiFi" {
		t.Fatalf("expected PreviousConnection MyHomeWiFi, got: %s", status.PreviousConnection)
	}
	if !strings.HasPrefix(status.QRCode, "data:image/png;base64,") {
		t.Fatalf("expected base64 PNG data URL for QR code, got: %s", status.QRCode)
	}

	// Stop hotspot and verify cleanup + reconnect
	err = mgr.StopHotspot(context.Background())
	if err != nil {
		t.Fatalf("unexpected StopHotspot error: %v", err)
	}

	st := mgr.GetStatus()
	if st.Active {
		t.Fatalf("expected hotspot to be inactive after Stop")
	}

	// Verify command calls included connection down, delete, and connection up
	var sawDown, sawDelete, sawRestore bool
	for _, call := range runner.calls {
		cmdStr := strings.Join(call, " ")
		if strings.Contains(cmdStr, "connection down skydrop-hotspot") {
			sawDown = true
		}
		if strings.Contains(cmdStr, "connection delete skydrop-hotspot") {
			sawDelete = true
		}
		if strings.Contains(cmdStr, "connection up id MyHomeWiFi") {
			sawRestore = true
		}
	}

	if !sawDown || !sawDelete {
		t.Fatalf("expected down and delete commands for skydrop-hotspot")
	}
	if !sawRestore {
		t.Fatalf("expected connection up id MyHomeWiFi to restore previous Wi-Fi connection")
	}
}

func TestCleanupLeftovers(t *testing.T) {
	runner := &mockRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			cmdStr := strings.Join(args, " ")
			if name == "nmcli" && strings.Contains(cmdStr, "connection show") {
				return []byte("HomeWiFi:uuid-1\nskydrop-hotspot:uuid-2\nSkyDrop-Hotspot-Old:uuid-3\n"), nil
			}
			return []byte(""), nil
		},
	}

	mgr := NewManager(runner)
	mgr.CleanupLeftovers(context.Background())

	var deleted []string
	for _, call := range runner.calls {
		if len(call) >= 4 && call[0] == "nmcli" && call[1] == "connection" && call[2] == "delete" {
			deleted = append(deleted, call[3])
		}
	}

	if len(deleted) != 2 {
		t.Fatalf("expected 2 leftover connections deleted, got: %v", deleted)
	}
	if deleted[0] != "skydrop-hotspot" || deleted[1] != "SkyDrop-Hotspot-Old" {
		t.Fatalf("unexpected deleted profiles: %v", deleted)
	}
}

func TestQueryStations(t *testing.T) {
	runner := &mockRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "ip" {
				return []byte("10.42.0.45 dev wlan0 lladdr 12:34:56:78:9a:bc REACHABLE\n"), nil
			}
			if name == "iw" {
				return []byte("Station 12:34:56:78:9a:bc (on wlan0)\n\tsignal:\t-42 dBm\n\trx bytes:\t1234\n"), nil
			}
			return []byte(""), nil
		},
	}

	mgr := NewManager(runner)
	clients := mgr.queryStations(context.Background(), "wlan0")

	if len(clients) != 1 {
		t.Fatalf("expected 1 client, got: %d", len(clients))
	}
	if clients[0].MAC != "12:34:56:78:9a:bc" {
		t.Fatalf("expected MAC 12:34:56:78:9a:bc, got: %s", clients[0].MAC)
	}
	if clients[0].IP != "10.42.0.45" {
		t.Fatalf("expected IP 10.42.0.45, got: %s", clients[0].IP)
	}
	if clients[0].Signal != "-42 dBm" {
		t.Fatalf("expected signal -42 dBm, got: %s", clients[0].Signal)
	}
}
