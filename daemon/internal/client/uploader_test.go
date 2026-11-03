package client

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestUploader_UploadFile_Success(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-client-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	testFilePath := filepath.Join(tmpDir, "sample.txt")
	testContent := []byte("Testing SkyDrop Go client file streaming!")
	if err := os.WriteFile(testFilePath, testContent, 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	var receivedFilename string
	var receivedAuth string
	var receivedBody []byte

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/upload" {
			http.NotFound(w, r)
			return
		}
		receivedFilename = r.Header.Get("X-Filename")
		receivedAuth = r.Header.Get("Authorization")
		var err error
		receivedBody, err = io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer srv.Close()

	uploader := NewUploader(true)

	var lastPercent int
	var lastWritten int64
	progressCallback := func(written, total int64, percent int) {
		lastWritten = written
		lastPercent = percent
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = uploader.UploadFile(ctx, srv.URL, testFilePath, "secret-token-123", progressCallback)
	if err != nil {
		t.Fatalf("UploadFile failed: %v", err)
	}

	if receivedFilename != "sample.txt" {
		t.Errorf("expected filename sample.txt, got %s", receivedFilename)
	}
	if receivedAuth != "Bearer secret-token-123" {
		t.Errorf("expected Authorization header Bearer secret-token-123, got %s", receivedAuth)
	}
	if string(receivedBody) != string(testContent) {
		t.Errorf("received body mismatch: %s != %s", string(receivedBody), string(testContent))
	}
	if lastPercent != 100 {
		t.Errorf("expected 100 percent progress, got %d", lastPercent)
	}
	if lastWritten != int64(len(testContent)) {
		t.Errorf("expected %d written bytes, got %d", len(testContent), lastWritten)
	}
}

func TestUploader_SendText_Success(t *testing.T) {
	var receivedAuth string
	var receivedBody []byte

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/text" {
			http.NotFound(w, r)
			return
		}
		receivedAuth = r.Header.Get("Authorization")
		var err error
		receivedBody, err = io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer srv.Close()

	uploader := NewUploader(true)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err := uploader.SendText(ctx, srv.URL, "https://github.com/skydrop", "token-xyz")
	if err != nil {
		t.Fatalf("SendText failed: %v", err)
	}

	if receivedAuth != "Bearer token-xyz" {
		t.Errorf("expected auth token Bearer token-xyz, got %s", receivedAuth)
	}
	expectedJSON := `{"text":"https://github.com/skydrop"}`
	if string(receivedBody) != expectedJSON {
		t.Errorf("expected %s, got %s", expectedJSON, string(receivedBody))
	}
}

func TestUploader_UploadFiles_Batch(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-batch-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	f1 := filepath.Join(tmpDir, "file1.txt")
	f2 := filepath.Join(tmpDir, "file2.txt")
	_ = os.WriteFile(f1, []byte("Content 1"), 0644)
	_ = os.WriteFile(f2, []byte("Content 2"), 0644)

	var uploadedFiles []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusOK)
			return
		}
		uploadedFiles = append(uploadedFiles, r.Header.Get("X-Filename"))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer srv.Close()

	uploader := NewUploader(true)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var batchCallCount int
	uploaded, err := uploader.UploadFiles(ctx, srv.URL, []string{f1, f2}, "test-token", func(idx, total int, name string, written, sz int64, pct int) {
		batchCallCount++
	})

	if err != nil {
		t.Fatalf("UploadFiles failed: %v", err)
	}
	if len(uploaded) != 2 {
		t.Errorf("expected 2 uploaded files, got %d", len(uploaded))
	}
	if len(uploadedFiles) != 2 {
		t.Errorf("expected server to receive 2 files, got %d", len(uploadedFiles))
	}
	if batchCallCount == 0 {
		t.Errorf("expected batch callbacks to fire")
	}
}

func TestUploader_Resumable(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-resume-client-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	filePath := filepath.Join(tmpDir, "resume.bin")
	fullContent := []byte("0123456789abcdefghij")
	_ = os.WriteFile(filePath, fullContent, 0644)

	var receivedOffset string
	var receivedBody []byte

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			// Simulate server having already received 10 bytes
			w.Header().Set("Upload-Offset", "10")
			w.WriteHeader(http.StatusOK)
			return
		}
		receivedOffset = r.Header.Get("Upload-Offset")
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer srv.Close()

	uploader := NewUploader(true)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = uploader.UploadFile(ctx, srv.URL, filePath, "test-token", nil)
	if err != nil {
		t.Fatalf("UploadFile failed: %v", err)
	}

	if receivedOffset != "10" {
		t.Errorf("expected Upload-Offset 10, got %s", receivedOffset)
	}
	if string(receivedBody) != string(fullContent[10:]) {
		t.Errorf("expected resumed chunk %q, got %q", string(fullContent[10:]), string(receivedBody))
	}
}

func TestUploader_ErrorClassification_HTTP(t *testing.T) {
	testCases := []struct {
		status   int
		expected string
		body     string
	}{
		{http.StatusUnauthorized, "UNAUTHORIZED", `{"error":"unauthorized"}`},
		{http.StatusForbidden, "UNAUTHORIZED", `Forbidden`},
		{http.StatusNotFound, "NOT_FOUND", `Not found`},
		{http.StatusRequestEntityTooLarge, "FILE_TOO_LARGE", `File exceeds limit`},
		{http.StatusInternalServerError, "RECEIVER_ERROR", `Internal disk error`},
	}

	for _, tc := range testCases {
		err := classifyHTTPError("http://192.168.1.5:8444/upload", tc.status, []byte(tc.body))
		if err.Category != tc.expected {
			t.Errorf("expected category %s for status %d, got %s", tc.expected, tc.status, err.Category)
		}
		if err.StatusCode != tc.status {
			t.Errorf("expected status code %d, got %d", tc.status, err.StatusCode)
		}
	}
}

func TestUploader_ErrorClassification_Network(t *testing.T) {
	errRefused := classifyNetworkError("http://192.168.1.5:8444/upload", errors.New("dial tcp: connection refused"))
	if errRefused.Category != "CONNECTION_REFUSED" {
		t.Errorf("expected CONNECTION_REFUSED, got %s", errRefused.Category)
	}

	errTLS := classifyNetworkError("https://192.168.1.5:8444/upload", errors.New("tls: bad certificate"))
	if errTLS.Category != "TLS_ERROR" {
		t.Errorf("expected TLS_ERROR, got %s", errTLS.Category)
	}

	errTimeout := classifyNetworkError("http://192.168.1.5:8444/upload", errors.New("context deadline exceeded"))
	if errTimeout.Category != "TIMEOUT" {
		t.Errorf("expected TIMEOUT, got %s", errTimeout.Category)
	}
}

