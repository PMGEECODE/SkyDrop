package client

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// QueuedTransfer represents a file transfer queued while the target device is offline.
type QueuedTransfer struct {
	ID             string    `json:"id"`
	TargetDeviceID string    `json:"target_device_id"`
	TargetName     string    `json:"target_name"`
	FilePath       string    `json:"file_path"`
	Filename       string    `json:"filename"`
	Size           int64     `json:"size"`
	QueuedAt       time.Time `json:"queued_at"`
}

// QueueManager manages persistent file transfers surviving daemon restarts.
type QueueManager struct {
	mu        sync.RWMutex
	storePath string
	items     []QueuedTransfer
}

// NewQueueManager initializes or loads the queue from disk.
func NewQueueManager(storePath string) (*QueueManager, error) {
	qm := &QueueManager{
		storePath: storePath,
		items:     make([]QueuedTransfer, 0),
	}

	if err := qm.loadFromDisk(); err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("failed to load transfer queue: %w", err)
	}

	return qm, nil
}

// Enqueue persists a new item to the queue.
func (qm *QueueManager) Enqueue(item QueuedTransfer) error {
	qm.mu.Lock()
	defer qm.mu.Unlock()

	if item.QueuedAt.IsZero() {
		item.QueuedAt = time.Now().UTC()
	}

	qm.items = append(qm.items, item)
	return qm.saveToDiskLocked()
}

// Dequeue removes an item by ID from the queue.
func (qm *QueueManager) Dequeue(id string) error {
	qm.mu.Lock()
	defer qm.mu.Unlock()

	filtered := make([]QueuedTransfer, 0, len(qm.items))
	for _, it := range qm.items {
		if it.ID != id {
			filtered = append(filtered, it)
		}
	}
	qm.items = filtered
	return qm.saveToDiskLocked()
}

// GetTransfersForDevice returns all items destined for a specific device.
func (qm *QueueManager) GetTransfersForDevice(deviceID string) []QueuedTransfer {
	qm.mu.RLock()
	defer qm.mu.RUnlock()

	matched := make([]QueuedTransfer, 0)
	for _, it := range qm.items {
		if it.TargetDeviceID == deviceID {
			matched = append(matched, it)
		}
	}
	return matched
}

// ListAll returns a snapshot of all queued items.
func (qm *QueueManager) ListAll() []QueuedTransfer {
	qm.mu.RLock()
	defer qm.mu.RUnlock()

	copyList := make([]QueuedTransfer, len(qm.items))
	copy(copyList, qm.items)
	return copyList
}

func (qm *QueueManager) loadFromDisk() error {
	qm.mu.Lock()
	defer qm.mu.Unlock()

	data, err := os.ReadFile(qm.storePath)
	if err != nil {
		return err
	}

	var items []QueuedTransfer
	if err := json.Unmarshal(data, &items); err != nil {
		return err
	}

	qm.items = items
	return nil
}

func (qm *QueueManager) saveToDiskLocked() error {
	dir := filepath.Dir(qm.storePath)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}

	data, err := json.MarshalIndent(qm.items, "", "  ")
	if err != nil {
		return err
	}

	tempPath := qm.storePath + ".tmp"
	if err := os.WriteFile(tempPath, data, 0600); err != nil {
		return err
	}

	return os.Rename(tempPath, qm.storePath)
}
