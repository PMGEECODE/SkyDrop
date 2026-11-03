package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/discovery"
	"skydrop/daemon/internal/server"
	"skydrop/daemon/internal/storage"
)

func main() {
	log.Println("[SkyDrop] Starting Linux daemon...")

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[SkyDrop] Configuration error: %v", err)
	}

	store, err := storage.NewStorage(cfg.StorageDir, cfg.MaxFileSize)
	if err != nil {
		log.Fatalf("[SkyDrop] Storage initialization error: %v", err)
	}

	srv, err := server.NewServer(cfg, store, nil)
	if err != nil {
		log.Fatalf("[SkyDrop] Server initialization error: %v", err)
	}

	// Prepare mDNS text records
	txtRecords := []string{
		"version=1.0.0",
		fmt.Sprintf("device=%s", cfg.DeviceName),
		fmt.Sprintf("tls=%t", cfg.EnableTLS),
	}
	if srv.Fingerprint() != "" {
		txtRecords = append(txtRecords, fmt.Sprintf("fingerprint=%s", srv.Fingerprint()))
	}

	advertiser := discovery.NewAdvertiser()
	if err := advertiser.Register(discovery.ServiceConfig{
		InstanceName: cfg.DeviceName,
		ServiceName:  cfg.ServiceName,
		Port:         cfg.Port,
		TextRecords:  txtRecords,
	}); err != nil {
		log.Printf("[SkyDrop] Warning: mDNS broadcast could not be started: %v", err)
	} else {
		log.Printf("[SkyDrop] mDNS service advertised as '%s' on %s (port %d)", cfg.DeviceName, cfg.ServiceName, cfg.Port)
	}
	defer advertiser.Shutdown()

	// Server runner
	serverErrCh := make(chan error, 1)
	go func() {
		proto := "HTTP"
		if cfg.EnableTLS {
			proto = "HTTPS"
		}
		log.Printf("[SkyDrop] %s server listening on :%d (storage: %s)", proto, cfg.Port, cfg.StorageDir)
		if srv.Fingerprint() != "" {
			log.Printf("[SkyDrop] TLS SHA-256 Fingerprint: %s", srv.Fingerprint())
		}
		if err := srv.Start(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErrCh <- err
		}
	}()

	// Wait for termination signal
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		log.Printf("[SkyDrop] Received signal %v, initiating graceful shutdown...", sig)
	case err := <-serverErrCh:
		log.Fatalf("[SkyDrop] Server fatal error: %v", err)
	}

	advertiser.Shutdown()

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("[SkyDrop] Graceful shutdown error: %v", err)
	} else {
		log.Println("[SkyDrop] Daemon stopped successfully.")
	}
}
