package history

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Direction indicates the flow of a transfer.
type Direction string

const (
	DirectionInbound  Direction = "inbound"
	DirectionOutbound Direction = "outbound"
)

// Status represents the final or current transfer status.
type Status string

const (
	StatusCompleted Status = "completed"
	StatusFailed    Status = "failed"
	StatusPending   Status = "pending"
)

// Entry records a single file or text transfer.
type Entry struct {
	ID           string    `json:"id"`
	Timestamp    time.Time `json:"timestamp"`
	Direction    Direction `json:"direction"`
	ItemType     string    `json:"item_type"` // "file" or "text"
	Filename     string    `json:"filename"`
	Size         int64     `json:"size"`
	PeerName     string    `json:"peer_name"`
	PeerURL      string    `json:"peer_url,omitempty"`
	Status       Status    `json:"status"`
	ErrorMessage string    `json:"error_message,omitempty"`
	SavedPath    string    `json:"saved_path,omitempty"`
}

// Manager manages a thread-safe, persisted list of transfer history entries.
type Manager struct {
	mu       sync.RWMutex
	filePath string
	maxItems int
	entries  []Entry
}

// NewManager initializes the history manager with a given file path and capacity.
func NewManager(filePath string, maxItems int) (*Manager, error) {
	if maxItems <= 0 {
		maxItems = 500
	}

	dir := filepath.Dir(filePath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create history directory %s: %w", dir, err)
	}

	m := &Manager{
		filePath: filePath,
		maxItems: maxItems,
		entries:  make([]Entry, 0),
	}

	if err := m.load(); err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("failed to load history from %s: %w", filePath, err)
	}

	return m, nil
}

func (m *Manager) load() error {
	data, err := os.ReadFile(m.filePath)
	if err != nil {
		return err
	}

	if len(data) == 0 {
		return nil
	}

	var loaded []Entry
	if err := json.Unmarshal(data, &loaded); err != nil {
		return fmt.Errorf("failed to parse history json: %w", err)
	}

	m.entries = loaded
	return nil
}

func (m *Manager) saveLocked() error {
	data, err := json.MarshalIndent(m.entries, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal history: %w", err)
	}

	tmpFile := fmt.Sprintf("%s.tmp.%d", m.filePath, time.Now().UnixNano())
	if err := os.WriteFile(tmpFile, data, 0600); err != nil {
		return fmt.Errorf("failed to write temp history: %w", err)
	}

	if err := os.Rename(tmpFile, m.filePath); err != nil {
		_ = os.Remove(tmpFile)
		return fmt.Errorf("failed to commit history file: %w", err)
	}

	return nil
}

// AddEntry prepends a new entry to the history and persists it to disk.
func (m *Manager) AddEntry(entry Entry) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if entry.ID == "" {
		entry.ID = fmt.Sprintf("hist-%d", time.Now().UnixNano())
	}
	if entry.Timestamp.IsZero() {
		entry.Timestamp = time.Now().UTC()
	}

	// Prepend newest entry first
	m.entries = append([]Entry{entry}, m.entries...)

	// Trim if exceeding max capacity
	if len(m.entries) > m.maxItems {
		m.entries = m.entries[:m.maxItems]
	}

	return m.saveLocked()
}

// ListEntries returns a snapshot of recorded entries up to limit.
func (m *Manager) ListEntries(limit int) []Entry {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if limit <= 0 || limit > len(m.entries) {
		limit = len(m.entries)
	}

	result := make([]Entry, limit)
	copy(result, m.entries[:limit])
	return result
}

// DeleteEntry removes a specific entry by its ID.
func (m *Manager) DeleteEntry(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	found := false
	filtered := make([]Entry, 0, len(m.entries))
	for _, e := range m.entries {
		if e.ID == id {
			found = true
			continue
		}
		filtered = append(filtered, e)
	}

	if !found {
		return nil
	}

	m.entries = filtered
	return m.saveLocked()
}

// Clear removes all history entries and persists the empty list.
func (m *Manager) Clear() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.entries = make([]Entry, 0)
	return m.saveLocked()
}
