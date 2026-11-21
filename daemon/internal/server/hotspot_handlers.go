package server

import (
	"encoding/json"
	"errors"
	"net/http"

	"skydrop/daemon/internal/hotspot"
)

// HotspotStartRequest defines the payload for starting a Wi-Fi hotspot.
type HotspotStartRequest struct {
	DisconnectCurrent  bool `json:"disconnect_current"`
	IdleTimeoutMinutes int  `json:"idle_timeout_minutes"`
}

func (s *Server) handleHotspotCapabilities(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET is supported on /api/hotspot/capabilities")
		return
	}

	caps, err := s.hotspotMgr.CheckCapabilities(r.Context())
	if err != nil && caps == nil {
		writeError(w, http.StatusInternalServerError, "capabilities_error", err.Error())
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"ok":   true,
		"data": caps,
	})
}

func (s *Server) handleHotspotStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /api/hotspot/start")
		return
	}

	var req HotspotStartRequest
	if r.Body != nil && r.ContentLength > 0 {
		_ = json.NewDecoder(r.Body).Decode(&req)
	}

	status, err := s.hotspotMgr.StartHotspot(r.Context(), hotspot.StartOptions{
		DisconnectCurrent:  req.DisconnectCurrent,
		IdleTimeoutMinutes: req.IdleTimeoutMinutes,
	})
	if err != nil {
		if errors.Is(err, hotspot.ErrNeedsDisconnect) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusConflict)
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"ok":      false,
				"error":   "needs_disconnect",
				"message": err.Error(),
			})
			return
		}
		writeError(w, http.StatusInternalServerError, "start_failed", err.Error())
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"ok":   true,
		"data": status,
	})
}

func (s *Server) handleHotspotStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /api/hotspot/stop")
		return
	}

	if err := s.hotspotMgr.StopHotspot(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "stop_failed", err.Error())
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"ok": true,
	})
}

func (s *Server) handleHotspotStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET is supported on /api/hotspot/status")
		return
	}

	status := s.hotspotMgr.GetStatus()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"ok":   true,
		"data": status,
	})
}
