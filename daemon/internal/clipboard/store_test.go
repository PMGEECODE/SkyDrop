package clipboard

import (
	"path/filepath"
	"testing"
	"time"
)

func TestStoreAddAndList(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "clipboard.json")

	store, err := NewStore(storePath, 5)
	if err != nil {
		t.Fatalf("NewStore failed: %v", err)
	}

	item1 := ClipboardItem{
		ID:           "item-1",
		Text:         "First copied item",
		SourceDevice: "laptop",
		CreatedAt:    time.Now().UTC().Add(-2 * time.Minute),
		OriginID:     "item-1",
	}

	added, err := store.Add(item1)
	if err != nil || !added {
		t.Fatalf("expected item1 to be added: added=%v, err=%v", added, err)
	}

	// Duplicate add with same ID
	added2, err := store.Add(item1)
	if err != nil || added2 {
		t.Fatalf("expected duplicate to not be added: added=%v, err=%v", added2, err)
	}

	items := store.List()
	if len(items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(items))
	}
	if items[0].Text != "First copied item" {
		t.Fatalf("unexpected text: %s", items[0].Text)
	}
}

func TestStorePinAndEviction(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "clipboard.json")

	// Max 2 unpinned items
	store, err := NewStore(storePath, 2)
	if err != nil {
		t.Fatalf("NewStore failed: %v", err)
	}

	// Add item 1
	store.Add(ClipboardItem{
		ID:        "item-1",
		Text:      "Text 1",
		CreatedAt: time.Now().UTC().Add(-3 * time.Minute),
	})

	// Add item 2 and pin it
	store.Add(ClipboardItem{
		ID:        "item-2",
		Text:      "Pinned Text 2",
		CreatedAt: time.Now().UTC().Add(-2 * time.Minute),
		Pinned:    true,
	})

	// Add item 3
	store.Add(ClipboardItem{
		ID:        "item-3",
		Text:      "Text 3",
		CreatedAt: time.Now().UTC().Add(-1 * time.Minute),
	})

	// Add item 4 -> should evict item-1 (oldest unpinned), but keep item-2 (pinned) and item-3, item-4
	store.Add(ClipboardItem{
		ID:        "item-4",
		Text:      "Text 4",
		CreatedAt: time.Now().UTC(),
	})

	items := store.List()
	// Should have item-2 (pinned), item-3, item-4 = 3 items total
	if len(items) != 3 {
		t.Fatalf("expected 3 items, got %d", len(items))
	}

	// Pinned should be first in List()
	if !items[0].Pinned || items[0].ID != "item-2" {
		t.Fatalf("expected pinned item-2 first, got %+v", items[0])
	}

	// Verify item-1 was evicted
	if _, found := store.GetByID("item-1"); found {
		t.Fatalf("expected item-1 to be evicted")
	}
}

func TestStoreDeleteAndClear(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "clipboard.json")

	store, err := NewStore(storePath, 10)
	if err != nil {
		t.Fatalf("NewStore failed: %v", err)
	}

	store.Add(ClipboardItem{ID: "it-1", Text: "Hello", Pinned: false})
	store.Add(ClipboardItem{ID: "it-2", Text: "Pinned", Pinned: true})

	// Delete item 1
	if err := store.Delete("it-1"); err != nil {
		t.Fatalf("Delete failed: %v", err)
	}
	if _, found := store.GetByID("it-1"); found {
		t.Fatalf("expected it-1 to be deleted")
	}

	// Clear with keepPinned=true
	store.Add(ClipboardItem{ID: "it-3", Text: "To be cleared"})
	if err := store.Clear(true); err != nil {
		t.Fatalf("Clear failed: %v", err)
	}

	items := store.List()
	if len(items) != 1 || items[0].ID != "it-2" {
		t.Fatalf("expected only pinned it-2 remaining, got %d items", len(items))
	}
}

func TestStoreTextLimit(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "clipboard.json")

	store, err := NewStore(storePath, 10)
	if err != nil {
		t.Fatalf("NewStore failed: %v", err)
	}

	largeText := make([]byte, MaxItemTextBytes+1)
	_, err = store.Add(ClipboardItem{ID: "large", Text: string(largeText)})
	if err == nil {
		t.Fatalf("expected error for text exceeding MaxItemTextBytes")
	}
}
