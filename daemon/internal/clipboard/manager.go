package clipboard

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"skydrop/daemon/internal/client"
	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/presence"
	"skydrop/daemon/internal/security"
)

// ClipboardSettings holds user-configurable clipboard behaviour.
type ClipboardSettings struct {
	SyncEnabled bool `json:"sync_enabled"`
	AutoCapture bool `json:"auto_capture"` // desktop only: watch system clipboard
	HistorySize int  `json:"history_size"`
}

// Manager orchestrates clipboard storage, peer broadcast, and sync.
type Manager struct {
	mu          sync.RWMutex
	store       *Store
	cfg         *config.Config
	pairManager *security.PairManager
	uploader    *client.Uploader
	presenceSvc *presence.Service
	settings    ClipboardSettings
	settingsFile string
}

// NewManager creates a Manager wired to the given dependencies.
func NewManager(
	store *Store,
	cfg *config.Config,
	pairManager *security.PairManager,
	uploader *client.Uploader,
	presenceSvc *presence.Service,
) *Manager {
	m := &Manager{
		store:        store,
		cfg:          cfg,
		pairManager:  pairManager,
		uploader:     uploader,
		presenceSvc:  presenceSvc,
		settingsFile: cfg.ClipboardSettingsFile,
		settings: ClipboardSettings{
			SyncEnabled: true,
			AutoCapture: false,
			HistorySize: 200,
		},
	}
	m.loadSettings()
	return m
}

// AddLocal creates a new clipboard item from local input, stores it, and broadcasts it to online peers.
func (m *Manager) AddLocal(text, sourceDevice string) (*ClipboardItem, error) {
	if len(text) > MaxItemTextBytes {
		return nil, fmt.Errorf("clipboard: text exceeds %d bytes", MaxItemTextBytes)
	}
	if text == "" {
		return nil, fmt.Errorf("clipboard: text cannot be empty")
	}

	id, err := GenerateUUID()
	if err != nil {
		return nil, fmt.Errorf("clipboard: failed to generate UUID: %w", err)
	}

	item := ClipboardItem{
		ID:           id,
		Text:         text,
		SourceDevice: sourceDevice,
		CreatedAt:    time.Now().UTC(),
		Pinned:       false,
		OriginID:     id, // local origin
	}

	added, err := m.store.Add(item)
	if err != nil {
		return nil, fmt.Errorf("clipboard: failed to store item: %w", err)
	}
	if !added {
		// Already exists; return existing
		existing, _ := m.store.GetByID(id)
		return &existing, nil
	}

	m.mu.RLock()
	syncEnabled := m.settings.SyncEnabled
	m.mu.RUnlock()

	if syncEnabled {
		go m.BroadcastToPeers(item)
	}

	return &item, nil
}

// AddRemote adds a clipboard item received from a peer without re-broadcasting it.
// Loop prevention: if OriginID is already present in the store, the item is skipped.
func (m *Manager) AddRemote(item ClipboardItem) error {
	if item.ID == "" {
		return fmt.Errorf("clipboard: remote item ID cannot be empty")
	}
	if len(item.Text) > MaxItemTextBytes {
		return fmt.Errorf("clipboard: remote item text exceeds %d bytes", MaxItemTextBytes)
	}

	// Loop prevention: check OriginID not already present
	if item.OriginID != "" && item.OriginID != item.ID {
		if _, exists := m.store.GetByID(item.OriginID); exists {
			return nil // already have the origin; skip
		}
	}

	if item.CreatedAt.IsZero() {
		item.CreatedAt = time.Now().UTC()
	}

	_, err := m.store.Add(item)
	return err
}

// BroadcastToPeers sends the clipboard item to all currently online paired peers.
// Each peer is contacted in its own goroutine; errors are logged but not returned.
func (m *Manager) BroadcastToPeers(item ClipboardItem) {
	if m.presenceSvc == nil || m.uploader == nil {
		return
	}

	presences := m.presenceSvc.GetAllPresence()
	peers := m.pairManager.ListPeers()

	// Build a map of peer ID -> auth token for quick lookup
	tokenByID := make(map[string]string, len(peers))
	for _, p := range peers {
		tokenByID[p.ID] = p.AuthToken
	}

	for _, pp := range presences {
		if !pp.IsPaired {
			continue
		}
		if pp.State != presence.StateOnline && pp.State != presence.StateBusy {
			continue
		}
		if pp.Host == "" || pp.Port <= 0 {
			continue
		}

		token, ok := tokenByID[pp.PeerID]
		if !ok {
			continue
		}

		scheme := "http"
		if pp.IsTLS {
			scheme = "https"
		}
		peerURL := fmt.Sprintf("%s://%s:%d", scheme, pp.Host, pp.Port)

		go func(url, authToken string, it ClipboardItem) {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			if err := m.uploader.PostClipboardItem(ctx, url, it, authToken); err != nil {
				log.Printf("[clipboard/manager] broadcast to %s failed: %v", url, err)
			}
		}(peerURL, token, item)
	}
}

// syncResponse is the JSON envelope returned by GET /clipboard/sync.
type syncResponse struct {
	IDs    []string `json:"ids"`
	Device string   `json:"device"`
}

// pushRequest is the JSON body for POST /clipboard/push.
type pushRequest struct {
	Items []ClipboardItem `json:"items"`
}

// SyncWithPeer fetches the peer's item IDs, compares against local store, and pushes missing items.
func (m *Manager) SyncWithPeer(peerURL, authToken string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// GET /clipboard/sync
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, peerURL+"/clipboard/sync", nil)
	if err != nil {
		return fmt.Errorf("clipboard: failed to build sync request: %w", err)
	}
	if authToken != "" {
		req.Header.Set("Authorization", "Bearer "+authToken)
	}

	httpClient := m.uploader.HTTPClient()
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 15 * time.Second}
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("clipboard: sync GET failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("clipboard: sync GET returned HTTP %d", resp.StatusCode)
	}

	var syncResp syncResponse
	if err := json.NewDecoder(resp.Body).Decode(&syncResp); err != nil {
		return fmt.Errorf("clipboard: failed to decode sync response: %w", err)
	}

	// Build set of peer's IDs
	peerIDs := make(map[string]struct{}, len(syncResp.IDs))
	for _, id := range syncResp.IDs {
		peerIDs[id] = struct{}{}
	}

	// Find local items the peer doesn't have
	localIDs := m.store.IDs()
	var missing []ClipboardItem
	for _, id := range localIDs {
		if _, hasPeer := peerIDs[id]; !hasPeer {
			if item, ok := m.store.GetByID(id); ok {
				missing = append(missing, item)
			}
		}
	}

	if len(missing) == 0 {
		return nil
	}

	// POST /clipboard/push
	body, err := json.Marshal(pushRequest{Items: missing})
	if err != nil {
		return fmt.Errorf("clipboard: failed to marshal push request: %w", err)
	}

	pushReq, err := http.NewRequestWithContext(ctx, http.MethodPost, peerURL+"/clipboard/push", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("clipboard: failed to build push request: %w", err)
	}
	pushReq.Header.Set("Content-Type", "application/json; charset=utf-8")
	if authToken != "" {
		pushReq.Header.Set("Authorization", "Bearer "+authToken)
	}

	pushResp, err := httpClient.Do(pushReq)
	if err != nil {
		return fmt.Errorf("clipboard: push POST failed: %w", err)
	}
	defer pushResp.Body.Close()

	if pushResp.StatusCode < 200 || pushResp.StatusCode >= 300 {
		return fmt.Errorf("clipboard: push POST returned HTTP %d", pushResp.StatusCode)
	}

	return nil
}

// GetSettings returns a copy of the current clipboard settings.
func (m *Manager) GetSettings() ClipboardSettings {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.settings
}

// SetSettings updates and persists clipboard settings.
func (m *Manager) SetSettings(s ClipboardSettings) error {
	if s.HistorySize <= 0 {
		s.HistorySize = 200
	}
	m.mu.Lock()
	m.settings = s
	m.mu.Unlock()
	return m.saveSettings()
}

// Store returns the underlying store for direct handler access.
func (m *Manager) Store() *Store {
	return m.store
}

func (m *Manager) loadSettings() {
	data, err := os.ReadFile(m.settingsFile)
	if err != nil {
		return // use defaults
	}
	var s ClipboardSettings
	if err := json.Unmarshal(data, &s); err != nil {
		log.Printf("[clipboard/manager] failed to parse settings: %v", err)
		return
	}
	if s.HistorySize <= 0 {
		s.HistorySize = 200
	}
	m.mu.Lock()
	m.settings = s
	m.mu.Unlock()
}

func (m *Manager) saveSettings() error {
	m.mu.RLock()
	s := m.settings
	m.mu.RUnlock()

	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return fmt.Errorf("clipboard: failed to marshal settings: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(m.settingsFile), 0755); err != nil {
		return fmt.Errorf("clipboard: failed to create settings directory: %w", err)
	}

	tmpPath := m.settingsFile + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0600); err != nil {
		return fmt.Errorf("clipboard: failed to write settings temp file: %w", err)
	}
	if err := os.Rename(tmpPath, m.settingsFile); err != nil {
		_ = os.Remove(tmpPath)
		return fmt.Errorf("clipboard: failed to commit settings file: %w", err)
	}
	return nil
}

// GenerateUUID generates a cryptographically random UUID v4.
func GenerateUUID() (string, error) {
	b := make([]byte, 16)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		return "", err
	}
	// Set version 4
	b[6] = (b[6] & 0x0f) | 0x40
	// Set variant bits
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}
