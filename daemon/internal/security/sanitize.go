package security

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unicode"
)

const MaxFilenameLength = 255

var (
	ErrEmptyFilename       = errors.New("filename cannot be empty")
	ErrInvalidFilename     = errors.New("filename contains invalid characters or path traversal")
	ErrFilenameTooLong     = errors.New("filename exceeds maximum allowed length")
)

// SanitizeFilename validates and cleans an untrusted filename received over the network.
// It prevents path traversal, strips directory paths, removes dangerous characters, and enforces length constraints.
func SanitizeFilename(rawName string) (string, error) {
	trimmed := strings.TrimSpace(rawName)
	if trimmed == "" {
		return "", ErrEmptyFilename
	}

	// Reject null bytes or ASCII control characters
	for _, r := range trimmed {
		if r == 0 || (unicode.IsControl(r) && r != '\t') {
			return "", ErrInvalidFilename
		}
	}

	// Normalize separators and extract strictly the base name
	clean := filepath.Base(filepath.Clean(strings.ReplaceAll(trimmed, "\\", "/")))
	if clean == "." || clean == "/" || clean == ".." {
		return "", ErrInvalidFilename
	}

	// Reject path traversal attempts explicitly
	if strings.Contains(clean, "/") || strings.Contains(clean, "\\") || clean == ".." {
		return "", ErrInvalidFilename
	}

	if len(clean) > MaxFilenameLength {
		return "", ErrFilenameTooLong
	}

	return clean, nil
}

// ResolveUniqueDestination resolves a non-colliding filepath in the target directory.
// If "file.txt" exists, it generates "file (1).txt", "file (2).txt", etc.
func ResolveUniqueDestination(dir, sanitizedFilename string) (string, error) {
	targetPath := filepath.Join(dir, sanitizedFilename)
	if _, err := os.Stat(targetPath); errors.Is(err, os.ErrNotExist) {
		return targetPath, nil
	}

	ext := filepath.Ext(sanitizedFilename)
	base := strings.TrimSuffix(sanitizedFilename, ext)

	for i := 1; i <= 10000; i++ {
		candidate := fmt.Sprintf("%s (%d)%s", base, i, ext)
		candidatePath := filepath.Join(dir, candidate)
		if _, err := os.Stat(candidatePath); errors.Is(err, os.ErrNotExist) {
			return candidatePath, nil
		}
	}

	return "", fmt.Errorf("unable to resolve non-colliding destination for filename: %s", sanitizedFilename)
}
