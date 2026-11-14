package clipboard

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// MaxItemTextBytes is the maximum allowed size for a single clipboard item's text.
const MaxItemTextBytes = 64 * 1024 // 64 KB

// ClipboardItem is a single clipboard entry shared across devices.
type ClipboardItem struct {
	ID           string    `json:"id"`
	Text         string    `json:"text"`
	SourceDevice string    `json:"source_device"`
	CreatedAt    time.Time `json:"created_at"`
	Pinned       bool      `json:"pinned"`
	OriginID     string    `json:"origin_id"` // same as ID when created locally; prevents re-broadcast loops
}

// Store is a thread-safe, disk-persisted clipboard item ring-buffer.
type Store struct {
	mu       sync.RWMutex
	filePath string
	maxItems int
	items    []ClipboardItem  // ordered: index 0 is newest
	idIndex  map[string]int   // id -> position in items slice
}

// NewStore creates a Store backed by the given file path and capacity limit.
// Existing items are loaded from disk on initialisation.
func NewStore(path string, maxItems int) (*Store, error) {
	if maxItems <= 0 {
		maxItems = 200
	}

	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("clipboard: failed to create directory %s: %w", dir, err)
	}

	s := &Store{
		filePath: path,
		maxItems: maxItems,
		items:    make([]ClipboardItem, 0),
		idIndex:  make(map[string]int),
	}

	if err := s.load(); err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("clipboard: failed to load from %s: %w", path, err)
	}

	return s, nil
}

func (s *Store) load() error {
	data, err := os.ReadFile(s.filePath)
	if err != nil {
		return err
	}
	if len(data) == 0 {
		return nil
	}
	var loaded []ClipboardItem
	if err := json.Unmarshal(data, &loaded); err != nil {
		return fmt.Errorf("failed to parse clipboard JSON: %w", err)
	}
	s.items = loaded
	s.rebuildIndex()
	return nil
}

// rebuildIndex reconstructs idIndex from the current items slice.
// Must be called under write lock.
func (s *Store) rebuildIndex() {
	s.idIndex = make(map[string]int, len(s.items))
	for i, item := range s.items {
		s.idIndex[item.ID] = i
	}
}

// persistLocked writes the current items to disk atomically.
// Must be called under write lock.
func (s *Store) persistLocked() error {
	data, err := json.MarshalIndent(s.items, "", "  ")
	if err != nil {
		return fmt.Errorf("clipboard: failed to marshal items: %w", err)
	}

	tmpPath := s.filePath + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0600); err != nil {
		return fmt.Errorf("clipboard: failed to write temp file: %w", err)
	}

	if err := os.Rename(tmpPath, s.filePath); err != nil {
		_ = os.Remove(tmpPath)
		return fmt.Errorf("clipboard: failed to commit clipboard file: %w", err)
	}
	return nil
}

// Add inserts an item into the store if its ID is not already present.
// When the unpinned item count exceeds maxItems, the oldest unpinned item is evicted.
// Returns (true, nil) when added, (false, nil) when deduplicated, or (false, err) on failure.
func (s *Store) Add(item ClipboardItem) (bool, error) {
	if item.ID == "" {
		return false, fmt.Errorf("clipboard: item ID cannot be empty")
	}
	if len(item.Text) > MaxItemTextBytes {
		return false, fmt.Errorf("clipboard: item text exceeds %d bytes", MaxItemTextBytes)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.idIndex[item.ID]; exists {
		return false, nil
	}

	// Count unpinned to enforce cap
	if !item.Pinned {
		unpinnedCount := 0
		for _, it := range s.items {
			if !it.Pinned {
				unpinnedCount++
			}
		}
		// Evict oldest unpinned items until we're one below capacity
		for unpinnedCount >= s.maxItems {
			evicted := false
			for i := len(s.items) - 1; i >= 0; i-- {
				if !s.items[i].Pinned {
					s.items = append(s.items[:i], s.items[i+1:]...)
					unpinnedCount--
					evicted = true
					break
				}
			}
			if !evicted {
				break
			}
		}
	}

	// Prepend newest item
	s.items = append([]ClipboardItem{item}, s.items...)
	s.rebuildIndex()

	if err := s.persistLocked(); err != nil {
		return false, err
	}
	return true, nil
}

// Delete removes an item by ID. Returns nil if the item did not exist.
func (s *Store) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, exists := s.idIndex[id]
	if !exists {
		return nil
	}

	s.items = append(s.items[:idx], s.items[idx+1:]...)
	s.rebuildIndex()
	return s.persistLocked()
}

// Pin sets the pinned state of an item by ID.
func (s *Store) Pin(id string, pinned bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx, exists := s.idIndex[id]
	if !exists {
		return fmt.Errorf("clipboard: item %q not found", id)
	}

	s.items[idx].Pinned = pinned
	return s.persistLocked()
}

// GetByID returns the item with the given ID and a boolean indicating whether it was found.
func (s *Store) GetByID(id string) (ClipboardItem, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	idx, exists := s.idIndex[id]
	if !exists {
		return ClipboardItem{}, false
	}
	return s.items[idx], true
}

// List returns all items: pinned first (newest among pinned first), then unpinned newest first.
func (s *Store) List() []ClipboardItem {
	s.mu.RLock()
	defer s.mu.RUnlock()

	pinned := make([]ClipboardItem, 0)
	unpinned := make([]ClipboardItem, 0)
	for _, item := range s.items {
		if item.Pinned {
			pinned = append(pinned, item)
		} else {
			unpinned = append(unpinned, item)
		}
	}

	// Sort each group newest first
	sortNewest := func(a []ClipboardItem) {
		sort.Slice(a, func(i, j int) bool {
			return a[i].CreatedAt.After(a[j].CreatedAt)
		})
	}
	sortNewest(pinned)
	sortNewest(unpinned)

	result := make([]ClipboardItem, 0, len(s.items))
	result = append(result, pinned...)
	result = append(result, unpinned...)
	return result
}

// IDs returns all stored item IDs for sync negotiation.
func (s *Store) IDs() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	ids := make([]string, 0, len(s.items))
	for _, item := range s.items {
		ids = append(ids, item.ID)
	}
	return ids
}

// Clear removes all items. When keepPinned is true, pinned items are retained.
func (s *Store) Clear(keepPinned bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if keepPinned {
		filtered := make([]ClipboardItem, 0)
		for _, item := range s.items {
			if item.Pinned {
				filtered = append(filtered, item)
			}
		}
		s.items = filtered
	} else {
		s.items = make([]ClipboardItem, 0)
	}

	s.rebuildIndex()
	return s.persistLocked()
}

// SetMaxItems updates the maximum capacity for unpinned items.
func (s *Store) SetMaxItems(max int) error {
	if max <= 0 {
		max = 50
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.maxItems = max
	return s.persistLocked()
}
