package history

import (
	"fmt"
	"path/filepath"
	"sync"
	"testing"
)

func TestHistoryManager_AddAndList(t *testing.T) {
	tmpDir := t.TempDir()
	historyFile := filepath.Join(tmpDir, "history.json")

	mgr, err := NewManager(historyFile, 3)
	if err != nil {
		t.Fatalf("Failed to initialize manager: %v", err)
	}

	if len(mgr.ListEntries(10)) != 0 {
		t.Fatalf("Expected empty history initially")
	}

	err = mgr.AddEntry(Entry{
		Filename:  "doc1.pdf",
		Direction: DirectionInbound,
		Size:      1024,
		PeerName:  "Pixel 8",
		Status:    StatusCompleted,
	})
	if err != nil {
		t.Fatalf("AddEntry failed: %v", err)
	}

	err = mgr.AddEntry(Entry{
		Filename:  "photo.jpg",
		Direction: DirectionOutbound,
		Size:      2048,
		PeerName:  "Pixel 8",
		Status:    StatusCompleted,
	})
	if err != nil {
		t.Fatalf("AddEntry failed: %v", err)
	}

	entries := mgr.ListEntries(10)
	if len(entries) != 2 {
		t.Fatalf("Expected 2 entries, got %d", len(entries))
	}

	// Newest first
	if entries[0].Filename != "photo.jpg" {
		t.Errorf("Expected newest entry first, got %s", entries[0].Filename)
	}

	// Test max items truncation
	err = mgr.AddEntry(Entry{Filename: "doc3.txt", Status: StatusCompleted})
	if err != nil {
		t.Fatalf("AddEntry failed: %v", err)
	}
	err = mgr.AddEntry(Entry{Filename: "doc4.txt", Status: StatusCompleted})
	if err != nil {
		t.Fatalf("AddEntry failed: %v", err)
	}

	entries = mgr.ListEntries(10)
	if len(entries) != 3 {
		t.Errorf("Expected max items 3, got %d", len(entries))
	}
	if entries[0].Filename != "doc4.txt" {
		t.Errorf("Expected newest entry 'doc4.txt', got %s", entries[0].Filename)
	}

	// Verify persistence by reloading
	reloaded, err := NewManager(historyFile, 10)
	if err != nil {
		t.Fatalf("Failed to reload manager: %v", err)
	}
	if len(reloaded.ListEntries(10)) != 3 {
		t.Errorf("Expected 3 persisted entries, got %d", len(reloaded.ListEntries(10)))
	}

	// Test clear
	if err := mgr.Clear(); err != nil {
		t.Fatalf("Clear failed: %v", err)
	}
	if len(mgr.ListEntries(10)) != 0 {
		t.Errorf("Expected 0 entries after clear")
	}
}

func TestHistoryManager_ConcurrentAccess(t *testing.T) {
	tmpDir := t.TempDir()
	historyFile := filepath.Join(tmpDir, "history_concurrent.json")

	mgr, err := NewManager(historyFile, 100)
	if err != nil {
		t.Fatalf("Failed to initialize manager: %v", err)
	}

	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			_ = mgr.AddEntry(Entry{
				Filename:  fmt.Sprintf("file-%d.bin", index),
				Direction: DirectionInbound,
				Size:      int64(index * 100),
				Status:    StatusCompleted,
			})
			_ = mgr.ListEntries(5)
		}(i)
	}

	wg.Wait()
	entries := mgr.ListEntries(100)
	if len(entries) != 20 {
		t.Errorf("Expected 20 entries after concurrent writes, got %d", len(entries))
	}
}
