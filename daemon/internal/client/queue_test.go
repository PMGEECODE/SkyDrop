package client

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestQueueManager_LifecycleAndPersistence(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-queue-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	queuePath := filepath.Join(tmpDir, "queued_transfers.json")

	qm, err := NewQueueManager(queuePath)
	if err != nil {
		t.Fatalf("failed to create queue manager: %v", err)
	}

	item1 := QueuedTransfer{
		ID:             "queue-1",
		TargetDeviceID: "peer-abc",
		TargetName:     "Test Phone",
		FilePath:       "/path/to/file1.png",
		Filename:       "file1.png",
		Size:           1024,
		QueuedAt:       time.Now().UTC(),
	}

	item2 := QueuedTransfer{
		ID:             "queue-2",
		TargetDeviceID: "peer-xyz",
		TargetName:     "Other Laptop",
		FilePath:       "/path/to/file2.zip",
		Filename:       "file2.zip",
		Size:           2048,
		QueuedAt:       time.Now().UTC(),
	}

	if err := qm.Enqueue(item1); err != nil {
		t.Fatalf("failed to enqueue item1: %v", err)
	}
	if err := qm.Enqueue(item2); err != nil {
		t.Fatalf("failed to enqueue item2: %v", err)
	}

	if len(qm.ListAll()) != 2 {
		t.Fatalf("expected 2 items, got %d", len(qm.ListAll()))
	}

	matched := qm.GetTransfersForDevice("peer-abc")
	if len(matched) != 1 || matched[0].ID != "queue-1" {
		t.Fatalf("expected 1 matched item for peer-abc, got %v", matched)
	}

	// Reload queue from disk to verify persistence across restarts
	qmReloaded, err := NewQueueManager(queuePath)
	if err != nil {
		t.Fatalf("failed to reload queue from disk: %v", err)
	}

	if len(qmReloaded.ListAll()) != 2 {
		t.Fatalf("expected 2 reloaded items, got %d", len(qmReloaded.ListAll()))
	}

	// Dequeue item1
	if err := qmReloaded.Dequeue("queue-1"); err != nil {
		t.Fatalf("failed to dequeue item1: %v", err)
	}

	if len(qmReloaded.ListAll()) != 1 {
		t.Fatalf("expected 1 item after dequeue, got %d", len(qmReloaded.ListAll()))
	}
}
