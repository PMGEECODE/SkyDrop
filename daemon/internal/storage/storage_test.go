package storage

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestStorage_SaveStream_Success(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-storage-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	store, err := NewStorage(tmpDir, 1024*1024)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	content := []byte("Hello SkyDrop streaming world!")
	info, err := store.SaveStream("document.txt", bytes.NewReader(content), int64(len(content)))
	if err != nil {
		t.Fatalf("SaveStream failed: %v", err)
	}

	if info.BytesWritten != int64(len(content)) {
		t.Errorf("expected %d bytes, got %d", len(content), info.BytesWritten)
	}
	if info.SanitizedName != "document.txt" {
		t.Errorf("expected sanitized name document.txt, got %s", info.SanitizedName)
	}

	savedBytes, err := os.ReadFile(info.SavedPath)
	if err != nil {
		t.Fatalf("failed to read saved file: %v", err)
	}
	if !bytes.Equal(savedBytes, content) {
		t.Errorf("saved content does not match original")
	}

	// Verify no stray .tmp files left behind
	files, err := filepath.Glob(filepath.Join(tmpDir, ".*.tmp"))
	if err != nil {
		t.Fatalf("failed to check for temp files: %v", err)
	}
	if len(files) > 0 {
		t.Errorf("unexpected temp files remaining: %v", files)
	}
}

func TestStorage_SaveStream_ExceedsMaxFileSize(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-storage-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	maxSize := int64(10)
	store, err := NewStorage(tmpDir, maxSize)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	// Test declared size exceeding max
	_, err = store.SaveStream("big.bin", bytes.NewReader([]byte("123")), 20)
	if !errors.Is(err, ErrFileTooLarge) {
		t.Fatalf("expected ErrFileTooLarge for declared size, got: %v", err)
	}

	// Test actual stream exceeding max
	oversizedContent := bytes.Repeat([]byte("A"), 25)
	_, err = store.SaveStream("big.bin", bytes.NewReader(oversizedContent), 5)
	if !errors.Is(err, ErrFileTooLarge) {
		t.Fatalf("expected ErrFileTooLarge for streaming size, got: %v", err)
	}

	// Verify no files saved
	entries, err := os.ReadDir(tmpDir)
	if err != nil {
		t.Fatalf("failed to read storage dir: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("expected empty directory after failed write, found %d entries", len(entries))
	}
}

func TestStorage_SaveStream_PathTraversalBlocked(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-storage-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	store, err := NewStorage(tmpDir, 1024)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	// Filename trying to escape storage dir
	info, err := store.SaveStream("../../../evil.sh", bytes.NewReader([]byte("echo evil")), 9)
	if err != nil {
		t.Fatalf("expected sanitized write, got error: %v", err)
	}

	// Should be stored safely inside tmpDir as evil.sh
	expectedPath := filepath.Join(tmpDir, "evil.sh")
	if info.SavedPath != expectedPath {
		t.Fatalf("expected path %s, got %s", expectedPath, info.SavedPath)
	}
}

func TestStorage_SaveStreamResumable(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-resumable-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	store, err := NewStorage(tmpDir, 1024*1024)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	part1 := []byte("Part 1 data... ")
	part2 := []byte("Part 2 completed!")
	totalExpected := int64(len(part1) + len(part2))

	// Chunk 1 at offset 0
	info1, complete1, err := store.SaveStreamResumable("large_file.txt", bytes.NewReader(part1), totalExpected, 0)
	if err != nil {
		t.Fatalf("Chunk 1 write failed: %v", err)
	}
	if complete1 {
		t.Errorf("Chunk 1 should not be complete")
	}
	if info1.TotalBytes != int64(len(part1)) {
		t.Errorf("expected total bytes %d, got %d", len(part1), info1.TotalBytes)
	}

	// Query offset
	offset, err := store.GetPartialOffset("large_file.txt")
	if err != nil {
		t.Fatalf("GetPartialOffset failed: %v", err)
	}
	if offset != int64(len(part1)) {
		t.Errorf("expected partial offset %d, got %d", len(part1), offset)
	}

	// Chunk 2 at offset = len(part1)
	info2, complete2, err := store.SaveStreamResumable("large_file.txt", bytes.NewReader(part2), totalExpected, offset)
	if err != nil {
		t.Fatalf("Chunk 2 write failed: %v", err)
	}
	if !complete2 {
		t.Errorf("Chunk 2 should complete the transfer")
	}
	if info2.TotalBytes != totalExpected {
		t.Errorf("expected total %d, got %d", totalExpected, info2.TotalBytes)
	}

	// Verify final file
	savedBytes, err := os.ReadFile(info2.SavedPath)
	if err != nil {
		t.Fatalf("failed to read completed file: %v", err)
	}
	if string(savedBytes) != string(part1)+string(part2) {
		t.Errorf("saved file content mismatch: got %q", string(savedBytes))
	}

	// Offset should be 0 now that partial file was renamed
	newOffset, _ := store.GetPartialOffset("large_file.txt")
	if newOffset != 0 {
		t.Errorf("expected offset 0 after completion, got %d", newOffset)
	}
}

func TestStorage_SaveStreamResumableWithProgress(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-storage-progress-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	store, err := NewStorage(tmpDir, 1024*1024)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	payload := bytes.Repeat([]byte("X"), 128*1024)
	var progressReports []int
	onProg := func(bytesWritten, totalBytes int64, percent int) {
		progressReports = append(progressReports, percent)
	}

	info, isComplete, err := store.SaveStreamResumableWithProgress("sample.bin", bytes.NewReader(payload), int64(len(payload)), 0, onProg)
	if err != nil {
		t.Fatalf("SaveStreamResumableWithProgress failed: %v", err)
	}
	if !isComplete {
		t.Fatalf("expected isComplete to be true")
	}
	if info.TotalBytes != int64(len(payload)) {
		t.Errorf("expected total %d, got %d", len(payload), info.TotalBytes)
	}
	if len(progressReports) == 0 {
		t.Errorf("expected at least one progress callback invocation")
	}
	// Last progress report should be 100%
	if progressReports[len(progressReports)-1] != 100 {
		t.Errorf("expected final progress report to be 100%%, got %d%%", progressReports[len(progressReports)-1])
	}
}

