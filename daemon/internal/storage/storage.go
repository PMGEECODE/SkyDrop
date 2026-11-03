package storage

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"skydrop/daemon/internal/security"
)

var (
	ErrFileTooLarge    = errors.New("file payload exceeds maximum allowed size")
	ErrOffsetMismatch  = errors.New("upload offset does not match current partial file size")
	ErrInvalidFilename = errors.New("invalid or unsafe filename provided")
)

// Storage handles streaming file storage operations with resumable support.
type Storage struct {
	baseDir     string
	maxFileSize int64
	mu          sync.Mutex
}

// SavedFileInfo contains metadata of a saved or in-progress file.
type SavedFileInfo struct {
	OriginalName  string    `json:"original_name"`
	SanitizedName string    `json:"sanitized_name"`
	SavedPath     string    `json:"saved_path"`
	BytesWritten  int64     `json:"bytes_written"`
	TotalBytes    int64     `json:"total_bytes"`
	ReceivedAt    time.Time `json:"received_at"`
}

// NewStorage creates a new Storage instance and ensures the base directory exists.
func NewStorage(baseDir string, maxFileSize int64) (*Storage, error) {
	if err := os.MkdirAll(baseDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create storage directory %s: %w", baseDir, err)
	}
	return &Storage{
		baseDir:     baseDir,
		maxFileSize: maxFileSize,
	}, nil
}

// SetBaseDir dynamically updates the storage destination directory.
func (s *Storage) SetBaseDir(dir string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create storage directory %s: %w", dir, err)
	}
	s.baseDir = dir
	return nil
}

// GetBaseDir returns the current destination directory path.
func (s *Storage) GetBaseDir() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.baseDir
}

// GetPartialOffset returns the number of bytes already received for an in-progress transfer.
func (s *Storage) GetPartialOffset(rawFilename string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	sanitizedName, err := security.SanitizeFilename(rawFilename)
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrInvalidFilename, err)
	}

	partPath := filepath.Join(s.baseDir, "."+sanitizedName+".part")
	stat, err := os.Stat(partPath)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, fmt.Errorf("failed to inspect partial file: %w", err)
	}

	return stat.Size(), nil
}

// SaveStream streams data from reader to an atomic temporary file, and renames on completion.
func (s *Storage) SaveStream(rawFilename string, src io.Reader, declaredSize int64) (*SavedFileInfo, error) {
	if declaredSize > s.maxFileSize {
		return nil, ErrFileTooLarge
	}
	info, _, err := s.SaveStreamResumable(rawFilename, src, 0, 0)
	return info, err
}

// ProgressFunc is called periodically as bytes are written to disk.
type ProgressFunc func(bytesWritten, totalBytes int64, percent int)

// SaveStreamResumable streams chunks into a partial file and commits atomically once complete.
// If offset > 0, it verifies and appends to the existing partial file.
func (s *Storage) SaveStreamResumable(
	rawFilename string,
	src io.Reader,
	declaredTotalSize int64,
	offset int64,
) (*SavedFileInfo, bool, error) {
	return s.SaveStreamResumableWithProgress(rawFilename, src, declaredTotalSize, offset, nil)
}

// SaveStreamResumableWithProgress streams chunks into a partial file, reporting incremental byte progress.
func (s *Storage) SaveStreamResumableWithProgress(
	rawFilename string,
	src io.Reader,
	declaredTotalSize int64,
	offset int64,
	onProgress ProgressFunc,
) (*SavedFileInfo, bool, error) {
	if declaredTotalSize > s.maxFileSize {
		return nil, false, ErrFileTooLarge
	}

	sanitizedName, err := security.SanitizeFilename(rawFilename)
	if err != nil {
		return nil, false, fmt.Errorf("%w: %v", ErrInvalidFilename, err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	partPath := filepath.Join(s.baseDir, "."+sanitizedName+".part")

	var file *os.File
	var currentSize int64
	var written int64

	if offset > 0 {
		stat, err := os.Stat(partPath)
		if err != nil {
			return nil, false, fmt.Errorf("partial file not found for offset %d: %w", offset, err)
		}
		if stat.Size() != offset {
			return nil, false, fmt.Errorf("%w: expected %d, got %d", ErrOffsetMismatch, offset, stat.Size())
		}
		currentSize = stat.Size()

		file, err = os.OpenFile(partPath, os.O_WRONLY|os.O_APPEND, 0644)
		if err != nil {
			return nil, false, fmt.Errorf("failed to open partial file for append: %w", err)
		}
	} else {
		file, err = os.OpenFile(partPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
		if err != nil {
			return nil, false, fmt.Errorf("failed to create partial file: %w", err)
		}
	}

	// Ensure file is closed and cleaned up on unexpected failure or oversize
	cleanupNeeded := true
	defer func() {
		_ = file.Close()
		if cleanupNeeded && (offset == 0 || currentSize+written > s.maxFileSize) {
			_ = os.Remove(partPath)
		}
	}()

	remainingAllowance := s.maxFileSize - currentSize
	if remainingAllowance <= 0 {
		return nil, false, ErrFileTooLarge
	}

	limitedReader := io.LimitReader(src, remainingAllowance+1)
	var copyErr error
	var lastProgressTime time.Time
	var lastPercent int = -1

	buf := make([]byte, 64*1024)
	for {
		nr, rerr := limitedReader.Read(buf)
		if nr > 0 {
			nw, werr := file.Write(buf[:nr])
			if nw > 0 {
				written += int64(nw)
			}
			if werr != nil {
				copyErr = fmt.Errorf("streaming write failed: %w", werr)
				break
			}
			if nr != nw {
				copyErr = fmt.Errorf("short write: read %d, wrote %d", nr, nw)
				break
			}
			if onProgress != nil && declaredTotalSize > 0 {
				currentTotal := currentSize + written
				percent := int((currentTotal * 100) / declaredTotalSize)
				if percent > 100 {
					percent = 100
				}
				now := time.Now()
				if percent != lastPercent || now.Sub(lastProgressTime) >= 80*time.Millisecond {
					lastPercent = percent
					lastProgressTime = now
					onProgress(currentTotal, declaredTotalSize, percent)
				}
			}
		}
		if rerr != nil {
			if !errors.Is(rerr, io.EOF) {
				copyErr = fmt.Errorf("streaming read failed: %w", rerr)
			}
			break
		}
	}
	if copyErr != nil {
		return nil, false, copyErr
	}

	newTotal := currentSize + written
	if newTotal > s.maxFileSize {
		return nil, false, ErrFileTooLarge
	}

	if err := file.Sync(); err != nil {
		return nil, false, fmt.Errorf("failed to sync file: %w", err)
	}

	// Check if transfer is complete:
	// If declaredTotalSize is provided (positive) and newTotal >= declaredTotalSize, or if offset == 0 and declaredTotalSize <= 0
	isComplete := (declaredTotalSize > 0 && newTotal >= declaredTotalSize) || (declaredTotalSize <= 0 && offset == 0)

	if isComplete {
		_ = file.Close()

		finalPath, err := security.ResolveUniqueDestination(s.baseDir, sanitizedName)
		if err != nil {
			return nil, false, fmt.Errorf("failed to resolve unique destination: %w", err)
		}

		if err := os.Rename(partPath, finalPath); err != nil {
			return nil, false, fmt.Errorf("failed to finalize completed transfer: %w", err)
		}

		cleanupNeeded = false
		return &SavedFileInfo{
			OriginalName:  rawFilename,
			SanitizedName: filepath.Base(finalPath),
			SavedPath:     finalPath,
			BytesWritten:  written,
			TotalBytes:    newTotal,
			ReceivedAt:    time.Now().UTC(),
		}, true, nil
	}

	cleanupNeeded = false
	return &SavedFileInfo{
		OriginalName:  rawFilename,
		SanitizedName: sanitizedName,
		SavedPath:     partPath,
		BytesWritten:  written,
		TotalBytes:    newTotal,
		ReceivedAt:    time.Now().UTC(),
	}, false, nil
}
