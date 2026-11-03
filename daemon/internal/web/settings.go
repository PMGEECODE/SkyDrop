package web

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"skydrop/daemon/internal/storage"
)

// SetStorage assigns the active storage manager to the WebController.
func (wc *WebController) SetStorage(s *storage.Storage) {
	wc.storage = s
}

// SettingsPayload represents configurable settings exposed to the desktop UI.
type SettingsPayload struct {
	DeviceName     string `json:"device_name"`
	StorageDir     string `json:"storage_dir"`
	RequirePairing bool   `json:"require_pairing"`
	Port           int    `json:"port"`
	EnableTLS      bool   `json:"enable_tls"`
	Fingerprint    string `json:"fingerprint,omitempty"`
}

// handleSettings handles retrieval and updating of daemon configuration settings.
func (wc *WebController) handleSettings(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")

	switch r.Method {
	case http.MethodGet:
		currentStorage := wc.cfg.StorageDir
		if wc.storage != nil {
			currentStorage = wc.storage.GetBaseDir()
		}

		_ = json.NewEncoder(w).Encode(SettingsPayload{
			DeviceName:     wc.cfg.DeviceName,
			StorageDir:     currentStorage,
			RequirePairing: wc.cfg.RequirePairing,
			Port:           wc.cfg.Port,
			EnableTLS:      wc.cfg.EnableTLS,
			Fingerprint:    wc.fingerprint,
		})

	case http.MethodPost:
		var update struct {
			DeviceName     *string `json:"device_name"`
			StorageDir     *string `json:"storage_dir"`
			RequirePairing *bool   `json:"require_pairing"`
		}

		if err := json.NewDecoder(r.Body).Decode(&update); err != nil {
			http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
			return
		}

		if update.DeviceName != nil {
			cleanName := strings.TrimSpace(*update.DeviceName)
			if cleanName != "" {
				wc.cfg.DeviceName = cleanName
				if wc.resolver != nil {
					wc.resolver.SetSelf(cleanName, wc.cfg.Port, wc.fingerprint)
				}
			}
		}

		if update.StorageDir != nil {
			cleanDir := strings.TrimSpace(*update.StorageDir)
			if cleanDir != "" {
				absDir, err := filepath.Abs(cleanDir)
				if err == nil {
					if err := os.MkdirAll(absDir, 0755); err == nil {
						wc.cfg.StorageDir = absDir
						if wc.storage != nil {
							_ = wc.storage.SetBaseDir(absDir)
						}
					}
				}
			}
		}

		if update.RequirePairing != nil {
			wc.cfg.RequirePairing = *update.RequirePairing
		}

		currentStorage := wc.cfg.StorageDir
		if wc.storage != nil {
			currentStorage = wc.storage.GetBaseDir()
		}

		_ = json.NewEncoder(w).Encode(SettingsPayload{
			DeviceName:     wc.cfg.DeviceName,
			StorageDir:     currentStorage,
			RequirePairing: wc.cfg.RequirePairing,
			Port:           wc.cfg.Port,
			EnableTLS:      wc.cfg.EnableTLS,
			Fingerprint:    wc.fingerprint,
		})

	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}

// handleUnpairPeer allows unpairing an authorized device by ID.
func (wc *WebController) handleUnpairPeer(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		ID string `json:"id"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		http.Error(w, "Missing or invalid peer ID", http.StatusBadRequest)
		return
	}

	if wc.pairManager != nil {
		if err := wc.pairManager.UnpairPeer(req.ID); err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]string{
		"status": "unpaired",
		"id":     req.ID,
	})
}
