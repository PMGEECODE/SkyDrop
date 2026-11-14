package clipboard

import (
	"path/filepath"
	"testing"
	"time"

	"skydrop/daemon/internal/config"
)

func TestManagerAddLocalAndLoopPrevention(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "clipboard.json")
	settingsPath := filepath.Join(tmpDir, "clipboard_settings.json")

	store, err := NewStore(storePath, 50)
	if err != nil {
		t.Fatalf("NewStore failed: %v", err)
	}

	cfg := &config.Config{
		ClipboardFile:         storePath,
		ClipboardSettingsFile: settingsPath,
	}

	mgr := NewManager(store, cfg, nil, nil, nil)

	// Add local item
	item, err := mgr.AddLocal("Hello from desktop", "desktop-1")
	if err != nil {
		t.Fatalf("AddLocal failed: %v", err)
	}

	if item.OriginID != item.ID {
		t.Fatalf("expected OriginID == ID for local item, got %s vs %s", item.OriginID, item.ID)
	}

	// Remote item with identical OriginID should be ignored (loop prevention)
	remoteLoopItem := ClipboardItem{
		ID:           "remote-echo-id",
		OriginID:     item.OriginID,
		Text:         "Hello from desktop",
		SourceDevice: "phone",
		CreatedAt:    time.Now().UTC(),
	}

	err = mgr.AddRemote(remoteLoopItem)
	if err != nil {
		t.Fatalf("AddRemote failed: %v", err)
	}

	// Store should still only contain the 1 original item
	items := store.List()
	if len(items) != 1 {
		t.Fatalf("expected 1 item after loop prevention, got %d", len(items))
	}

	// Remote item with new OriginID should be added
	remoteValidItem := ClipboardItem{
		ID:           "remote-unique-id",
		OriginID:     "remote-unique-id",
		Text:         "Hello from phone",
		SourceDevice: "phone",
		CreatedAt:    time.Now().UTC(),
	}

	err = mgr.AddRemote(remoteValidItem)
	if err != nil {
		t.Fatalf("AddRemote valid failed: %v", err)
	}

	if len(store.List()) != 2 {
		t.Fatalf("expected 2 items, got %d", len(store.List()))
	}
}

func TestManagerSettingsPersistence(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "clipboard.json")
	settingsPath := filepath.Join(tmpDir, "clipboard_settings.json")

	store, _ := NewStore(storePath, 50)
	cfg := &config.Config{
		ClipboardFile:         storePath,
		ClipboardSettingsFile: settingsPath,
	}

	mgr := NewManager(store, cfg, nil, nil, nil)
	s := mgr.GetSettings()
	if s.AutoCapture {
		t.Fatalf("AutoCapture should default to false")
	}

	s.AutoCapture = true
	s.HistorySize = 100
	if err := mgr.SetSettings(s); err != nil {
		t.Fatalf("SetSettings failed: %v", err)
	}

	// New manager instance should load saved settings
	mgr2 := NewManager(store, cfg, nil, nil, nil)
	s2 := mgr2.GetSettings()
	if !s2.AutoCapture || s2.HistorySize != 100 {
		t.Fatalf("expected loaded settings with AutoCapture=true, HistorySize=100, got %+v", s2)
	}
}
