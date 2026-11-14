package server

import (
	"encoding/json"
	"net/http"
	"strings"

	"skydrop/daemon/internal/clipboard"
)

// ClipboardAddRequest defines the body for adding a clipboard item.
type ClipboardAddRequest struct {
	ID           string `json:"id,omitempty"`
	Text         string `json:"text"`
	SourceDevice string `json:"source_device,omitempty"`
	OriginID     string `json:"origin_id,omitempty"`
	Pinned       bool   `json:"pinned,omitempty"`
}

// ClipboardDeleteRequest defines the body for deleting a clipboard item.
type ClipboardDeleteRequest struct {
	ID string `json:"id"`
}

// ClipboardPinRequest defines the body for pinning/unpinning a clipboard item.
type ClipboardPinRequest struct {
	ID     string `json:"id"`
	Pinned bool   `json:"pinned"`
}

// ClipboardClearRequest defines the body for clearing clipboard items.
type ClipboardClearRequest struct {
	KeepPinned bool `json:"keep_pinned"`
}

// ClipboardPushRequest defines the body for pushing missing items during sync.
type ClipboardPushRequest struct {
	Items []clipboard.ClipboardItem `json:"items"`
}

func (s *Server) handleClipboardAdd(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /clipboard/add")
		return
	}

	var req ClipboardAddRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "Failed to parse JSON body")
		return
	}

	trimmed := strings.TrimSpace(req.Text)
	if trimmed == "" {
		writeError(w, http.StatusBadRequest, "empty_text", "Clipboard text cannot be empty")
		return
	}

	if len(req.Text) > clipboard.MaxItemTextBytes {
		writeError(w, http.StatusBadRequest, "text_too_large", "Clipboard text exceeds 64 KB limit")
		return
	}

	if s.clipboardMgr == nil {
		writeError(w, http.StatusInternalServerError, "clipboard_unavailable", "Clipboard manager is not initialized")
		return
	}

	sourceDevice := req.SourceDevice
	if sourceDevice == "" {
		sourceDevice = s.cfg.DeviceName
	}

	// If origin_id is set or it came from a remote peer with an ID, treat as remote add (loop prevention)
	if req.OriginID != "" || req.ID != "" {
		item := clipboard.ClipboardItem{
			ID:           req.ID,
			Text:         req.Text,
			SourceDevice: sourceDevice,
			OriginID:     req.OriginID,
			Pinned:       req.Pinned,
		}
		if item.ID == "" {
			item.ID, _ = clipboard.GenerateUUID()
		}
		if item.OriginID == "" {
			item.OriginID = item.ID
		}
		if err := s.clipboardMgr.AddRemote(item); err != nil {
			writeError(w, http.StatusInternalServerError, "store_error", err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"status": "success",
			"item":   item,
		})
		return
	}

	// Otherwise, it's a locally generated item; store and broadcast to online peers
	item, err := s.clipboardMgr.AddLocal(req.Text, sourceDevice)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status": "success",
		"item":   item,
	})
}

func (s *Server) handleClipboardList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET is supported on /clipboard/list")
		return
	}

	if s.clipboardMgr == nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"items": []clipboard.ClipboardItem{},
			"count": 0,
		})
		return
	}

	items := s.clipboardMgr.Store().List()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"items": items,
		"count": len(items),
	})
}

func (s *Server) handleClipboardDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST or DELETE is supported")
		return
	}

	var req ClipboardDeleteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_id", "Missing or invalid item ID")
		return
	}

	if s.clipboardMgr != nil {
		_ = s.clipboardMgr.Store().Delete(req.ID)
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"status": "deleted",
		"id":     req.ID,
	})
}

func (s *Server) handleClipboardPin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported")
		return
	}

	var req ClipboardPinRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_request", "Missing or invalid pin request")
		return
	}

	if s.clipboardMgr != nil {
		if err := s.clipboardMgr.Store().Pin(req.ID, req.Pinned); err != nil {
			writeError(w, http.StatusNotFound, "not_found", err.Error())
			return
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status": "updated",
		"id":     req.ID,
		"pinned": req.Pinned,
	})
}

func (s *Server) handleClipboardClear(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST or DELETE is supported")
		return
	}

	var req ClipboardClearRequest
	_ = json.NewDecoder(r.Body).Decode(&req)

	if s.clipboardMgr != nil {
		_ = s.clipboardMgr.Store().Clear(req.KeepPinned)
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"status": "cleared",
	})
}

func (s *Server) handleClipboardSync(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET is supported on /clipboard/sync")
		return
	}

	var ids []string
	if s.clipboardMgr != nil {
		ids = s.clipboardMgr.Store().IDs()
	} else {
		ids = []string{}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ids":    ids,
		"device": s.cfg.DeviceName,
	})
}

func (s *Server) handleClipboardPush(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only POST is supported on /clipboard/push")
		return
	}

	var req ClipboardPushRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "Failed to parse push JSON body")
		return
	}

	addedCount := 0
	if s.clipboardMgr != nil {
		for _, item := range req.Items {
			if err := s.clipboardMgr.AddRemote(item); err == nil {
				addedCount++
			}
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":      "success",
		"added_count": addedCount,
	})
}

func (s *Server) handleClipboardSettings(w http.ResponseWriter, r *http.Request) {
	if s.clipboardMgr == nil {
		writeError(w, http.StatusInternalServerError, "clipboard_unavailable", "Clipboard manager not initialized")
		return
	}

	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, s.clipboardMgr.GetSettings())
		return
	}

	if r.Method == http.MethodPost {
		var settings clipboard.ClipboardSettings
		if err := json.NewDecoder(r.Body).Decode(&settings); err != nil {
			writeError(w, http.StatusBadRequest, "invalid_json", "Failed to parse settings JSON")
			return
		}
		if err := s.clipboardMgr.SetSettings(settings); err != nil {
			writeError(w, http.StatusInternalServerError, "save_error", err.Error())
			return
		}
		if settings.HistorySize > 0 {
			_ = s.clipboardMgr.Store().SetMaxItems(settings.HistorySize)
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"status":   "updated",
			"settings": s.clipboardMgr.GetSettings(),
		})
		return
	}

	writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Only GET and POST supported")
}
