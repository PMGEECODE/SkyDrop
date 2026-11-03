package security

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSanitizeFilename(t *testing.T) {
	tests := []struct {
		name      string
		raw       string
		expected  string
		expectErr error
	}{
		{
			name:      "simple valid filename",
			raw:       "photo.jpg",
			expected:  "photo.jpg",
			expectErr: nil,
		},
		{
			name:      "filename with spaces",
			raw:       "my presentation 2026.pdf",
			expected:  "my presentation 2026.pdf",
			expectErr: nil,
		},
		{
			name:      "path traversal unix style",
			raw:       "../../../../etc/passwd",
			expected:  "passwd",
			expectErr: nil,
		},
		{
			name:      "path traversal windows style",
			raw:       "..\\..\\Windows\\System32\\cmd.exe",
			expected:  "cmd.exe",
			expectErr: nil,
		},
		{
			name:      "empty filename",
			raw:       "",
			expected:  "",
			expectErr: ErrEmptyFilename,
		},
		{
			name:      "whitespace only",
			raw:       "   ",
			expected:  "",
			expectErr: ErrEmptyFilename,
		},
		{
			name:      "null byte injection",
			raw:       "malicious\x00file.png",
			expected:  "",
			expectErr: ErrInvalidFilename,
		},
		{
			name:      "pure dot traversal",
			raw:       "..",
			expected:  "",
			expectErr: ErrInvalidFilename,
		},
		{
			name:      "filename exceeding max length",
			raw:       strings.Repeat("a", MaxFilenameLength+1) + ".txt",
			expected:  "",
			expectErr: ErrFilenameTooLong,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			res, err := SanitizeFilename(tt.raw)
			if tt.expectErr != nil {
				if !errors.Is(err, tt.expectErr) {
					t.Fatalf("expected error %v, got %v", tt.expectErr, err)
				}
			} else {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if res != tt.expected {
					t.Fatalf("expected %s, got %s", tt.expected, res)
				}
			}
		})
	}
}

func TestResolveUniqueDestination(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-unique-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	// Test non-existing file
	p1, err := ResolveUniqueDestination(tmpDir, "report.pdf")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	expected1 := filepath.Join(tmpDir, "report.pdf")
	if p1 != expected1 {
		t.Fatalf("expected %s, got %s", expected1, p1)
	}

	// Create file report.pdf
	if err := os.WriteFile(p1, []byte("content"), 0600); err != nil {
		t.Fatalf("failed to write file: %v", err)
	}

	// Test collision resolution (should be report (1).pdf)
	p2, err := ResolveUniqueDestination(tmpDir, "report.pdf")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	expected2 := filepath.Join(tmpDir, "report (1).pdf")
	if p2 != expected2 {
		t.Fatalf("expected %s, got %s", expected2, p2)
	}

	// Create report (1).pdf and test next collision
	if err := os.WriteFile(p2, []byte("content"), 0600); err != nil {
		t.Fatalf("failed to write file: %v", err)
	}

	p3, err := ResolveUniqueDestination(tmpDir, "report.pdf")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	expected3 := filepath.Join(tmpDir, "report (2).pdf")
	if p3 != expected3 {
		t.Fatalf("expected %s, got %s", expected3, p3)
	}
}
