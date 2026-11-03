package server

import (
	"context"
	"crypto/tls"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/storage"
)

func TestServer_StartAndShutdown(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-start-shutdown-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	cfg := &config.Config{
		Port:        0, // allocate random available port
		DeviceName:  "TestDevice",
		ServiceName: "_filedrop._tcp",
		StorageDir:  tmpDir,
		CertFile:    filepath.Join(tmpDir, "cert.pem"),
		KeyFile:     filepath.Join(tmpDir, "key.pem"),
		MaxFileSize:       1024 * 1024,
		EnableTLS:         true,
		PairedDevicesFile: filepath.Join(tmpDir, "paired.json"),
		RequirePairing:    false,
	}

	store, err := storage.NewStorage(tmpDir, cfg.MaxFileSize)
	if err != nil {
		t.Fatalf("failed to create storage: %v", err)
	}

	srv, err := NewServer(cfg, store, nil)
	if err != nil {
		t.Fatalf("NewServer failed: %v", err)
	}

	if srv.Fingerprint() == "" {
		t.Errorf("expected non-empty TLS fingerprint")
	}

	serverErrCh := make(chan error, 1)
	go func() {
		if err := srv.Start(); err != nil && err != http.ErrServerClosed {
			serverErrCh <- err
		}
	}()

	// Wait briefly for server to bind listener
	time.Sleep(100 * time.Millisecond)

	addr := srv.Addr()
	if addr == "" {
		t.Fatalf("expected non-empty bound address")
	}

	// Test request with TLS
	tr := &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}
	client := &http.Client{Transport: tr, Timeout: 2 * time.Second}

	resp, err := client.Get("https://" + addr + "/info")
	if err != nil {
		t.Fatalf("failed to query server: %v", err)
	}
	resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK, got: %d", resp.StatusCode)
	}

	// Graceful shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		t.Fatalf("server shutdown failed: %v", err)
	}

	select {
	case err := <-serverErrCh:
		t.Fatalf("server exited with error: %v", err)
	default:
	}
}
