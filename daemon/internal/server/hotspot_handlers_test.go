package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"skydrop/daemon/internal/hotspot"
)

type mockHotspotRunner struct {
	runFunc func(ctx context.Context, name string, args ...string) ([]byte, error)
}

func (m *mockHotspotRunner) LookPath(file string) (string, error) {
	return "/usr/bin/" + file, nil
}

func (m *mockHotspotRunner) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	if m.runFunc != nil {
		return m.runFunc(ctx, name, args...)
	}
	return []byte(""), nil
}

func setupTestServerWithHotspot(t *testing.T, runner hotspot.CmdRunner) *Server {
	s := &Server{
		hotspotMgr: hotspot.NewManager(runner),
	}
	return s
}

func TestHandleHotspotCapabilities(t *testing.T) {
	runner := &mockHotspotRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("wlan0:wifi:connected:MyWiFi\n"), nil
			}
			if name == "iw" && len(args) >= 1 && args[0] == "list" {
				return []byte("Supported interface modes:\n\t * AP\n"), nil
			}
			return []byte(""), nil
		},
	}

	s := setupTestServerWithHotspot(t, runner)

	req := httptest.NewRequest(http.MethodGet, "/api/hotspot/capabilities", nil)
	w := httptest.NewRecorder()
	s.handleHotspotCapabilities(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got: %d (%s)", w.Code, w.Body.String())
	}

	var resp struct {
		OK   bool                 `json:"ok"`
		Data hotspot.Capabilities `json:"data"`
	}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if !resp.OK || !resp.Data.Supported || !resp.Data.SupportsAP {
		t.Fatalf("expected supported hotspot capabilities, got: %+v", resp.Data)
	}
}

func TestHandleHotspotStartAndStop(t *testing.T) {
	runner := &mockHotspotRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("wlan0:wifi:disconnected:\n"), nil
			}
			if name == "iw" && len(args) >= 1 && args[0] == "list" {
				return []byte("Supported interface modes:\n\t * AP\n"), nil
			}
			if name == "ip" {
				return []byte("wlan0 inet 10.42.0.1/24\n"), nil
			}
			return []byte(""), nil
		},
	}

	s := setupTestServerWithHotspot(t, runner)

	// 1. Start Hotspot
	startBody := `{"disconnect_current": true, "idle_timeout_minutes": 5}`
	req := httptest.NewRequest(http.MethodPost, "/api/hotspot/start", strings.NewReader(startBody))
	w := httptest.NewRecorder()
	s.handleHotspotStart(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 OK on start, got: %d (%s)", w.Code, w.Body.String())
	}

	var startResp struct {
		OK   bool           `json:"ok"`
		Data hotspot.Status `json:"data"`
	}
	if err := json.NewDecoder(w.Body).Decode(&startResp); err != nil {
		t.Fatalf("failed to decode start response: %v", err)
	}
	if !startResp.Data.Active || !strings.HasPrefix(startResp.Data.SSID, "SkyDrop-") {
		t.Fatalf("expected active hotspot, got: %+v", startResp.Data)
	}

	// 2. Query Status
	statusReq := httptest.NewRequest(http.MethodGet, "/api/hotspot/status", nil)
	statusW := httptest.NewRecorder()
	s.handleHotspotStatus(statusW, statusReq)

	if statusW.Code != http.StatusOK {
		t.Fatalf("expected 200 OK on status, got: %d", statusW.Code)
	}

	// 3. Stop Hotspot
	stopReq := httptest.NewRequest(http.MethodPost, "/api/hotspot/stop", nil)
	stopW := httptest.NewRecorder()
	s.handleHotspotStop(stopW, stopReq)

	if stopW.Code != http.StatusOK {
		t.Fatalf("expected 200 OK on stop, got: %d", stopW.Code)
	}

	// 4. Query Status again
	statusReq2 := httptest.NewRequest(http.MethodGet, "/api/hotspot/status", nil)
	statusW2 := httptest.NewRecorder()
	s.handleHotspotStatus(statusW2, statusReq2)

	var st2 struct {
		OK   bool           `json:"ok"`
		Data hotspot.Status `json:"data"`
	}
	_ = json.NewDecoder(statusW2.Body).Decode(&st2)
	if st2.Data.Active {
		t.Fatalf("expected hotspot to be inactive after stop")
	}
}

func TestHandleHotspotStart_NeedsDisconnectConflict(t *testing.T) {
	runner := &mockHotspotRunner{
		runFunc: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			if name == "nmcli" && len(args) >= 2 && args[0] == "general" && args[1] == "status" {
				return []byte("STATE: connected"), nil
			}
			if name == "nmcli" && len(args) >= 4 && args[3] == "device" {
				return []byte("wlan0:wifi:connected:MyOfficeWiFi\n"), nil
			}
			if name == "iw" && len(args) >= 1 && args[0] == "list" {
				return []byte("Supported interface modes:\n\t * AP\ninterface combinations are not supported\n"), nil
			}
			return []byte(""), nil
		},
	}

	s := setupTestServerWithHotspot(t, runner)

	req := httptest.NewRequest(http.MethodPost, "/api/hotspot/start", strings.NewReader(`{"disconnect_current": false}`))
	w := httptest.NewRecorder()
	s.handleHotspotStart(w, req)

	if w.Code != http.StatusConflict {
		t.Fatalf("expected 409 Conflict when disconnect_current is false on busy adapter, got: %d (%s)", w.Code, w.Body.String())
	}

	var resp map[string]interface{}
	_ = json.NewDecoder(w.Body).Decode(&resp)
	if resp["error"] != "needs_disconnect" {
		t.Fatalf("expected error='needs_disconnect', got: %v", resp["error"])
	}
}
