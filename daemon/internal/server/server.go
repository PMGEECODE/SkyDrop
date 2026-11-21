package server

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"time"

	"skydrop/daemon/internal/client"
	"skydrop/daemon/internal/clipboard"
	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/discovery"
	"skydrop/daemon/internal/history"
	"skydrop/daemon/internal/hotspot"
	"skydrop/daemon/internal/presence"
	"skydrop/daemon/internal/security"
	"skydrop/daemon/internal/storage"
	"skydrop/daemon/internal/ui"
	"skydrop/daemon/internal/web"
)

// Server represents the SkyDrop HTTP/HTTPS daemon server.
type Server struct {
	cfg             *config.Config
	storage         *storage.Storage
	pairManager     *security.PairManager
	resolver        *discovery.PeerResolver
	uploader        *client.Uploader
	notifier        *ui.Notifier
	history         *history.Manager
	presenceService *presence.Service
	httpServer      *http.Server
	fingerprint     string
	listener        net.Listener
	isTransferring  func() bool
	webCtrl         *web.WebController
	clipboardMgr    *clipboard.Manager
	clipboardWatcher *clipboard.Watcher
	hotspotMgr      *hotspot.Manager
}

// NewServer initializes the server with storage, pairing manager, routes, and security configurations.
func NewServer(cfg *config.Config, store *storage.Storage, pm *security.PairManager) (*Server, error) {
	if pm == nil {
		var err error
		pm, err = security.NewPairManager(cfg.PairedDevicesFile)
		if err != nil {
			return nil, fmt.Errorf("failed to initialize pair manager: %w", err)
		}
	}

	resolver := discovery.NewPeerResolver()
	resolver.SetSelf(cfg.DeviceName, cfg.Port, "")
	uploader := client.NewUploader(true)
	uploader.SetLocalDeviceID(cfg.DeviceName)
	notifier := ui.NewNotifier()
	histMgr, _ := history.NewManager(cfg.HistoryFile, 500)
	presenceSvc := presence.NewService(pm, resolver, cfg.DeviceName, cfg.DeviceName)
	clipStore, _ := clipboard.NewStore(cfg.ClipboardFile, 200)
	clipMgr := clipboard.NewManager(clipStore, cfg, pm, uploader, presenceSvc)
	clipWatcher := clipboard.NewWatcher()
	if clipWatcher != nil {
		clipWatcher.Start(func(text string) {
			if clipMgr.GetSettings().AutoCapture && clipMgr.GetSettings().SyncEnabled {
				_, _ = clipMgr.AddLocal(text, cfg.DeviceName)
			}
		})
	}

	hotspotMgr := hotspot.NewManager(nil)
	hotspotMgr.CleanupLeftovers(context.Background())

	s := &Server{
		cfg:              cfg,
		storage:          store,
		pairManager:      pm,
		resolver:         resolver,
		uploader:         uploader,
		notifier:         notifier,
		history:          histMgr,
		presenceService:  presenceSvc,
		clipboardMgr:     clipMgr,
		clipboardWatcher: clipWatcher,
		hotspotMgr:       hotspotMgr,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/info", s.handleInfo)
	mux.HandleFunc("/health", s.requireAuth(s.handleHealth))
	mux.HandleFunc("/presence/leave", s.requireAuth(s.handlePresenceLeave))
	mux.HandleFunc("/upload", s.requireAuth(s.handleUpload))
	mux.HandleFunc("/text", s.requireAuth(s.handleText))
	mux.HandleFunc("/pair/start", s.handlePairStart)
	mux.HandleFunc("/pair/confirm", s.handlePairConfirm)
	mux.HandleFunc("/pair/peers", s.handleListPeers)
	mux.HandleFunc("/pair/request", s.handlePairRequest)
	mux.HandleFunc("/pair/request/status", s.handlePairRequestStatus)
	mux.HandleFunc("/pair/request/cancel", s.handlePairRequestCancel)

	// Pairing API routes for local Electron UI
	mux.HandleFunc("/api/pair/incoming", s.handleIncomingPairRequests)
	mux.HandleFunc("/api/pair/incoming/accept", s.handleIncomingPairAccept)
	mux.HandleFunc("/api/pair/incoming/decline", s.handleIncomingPairDecline)
	mux.HandleFunc("/api/pair/initiate", s.handlePairInitiate)

	// Clipboard routes (authenticated for network peers)
	mux.HandleFunc("/clipboard/add", s.requireAuth(s.handleClipboardAdd))
	mux.HandleFunc("/clipboard/list", s.requireAuth(s.handleClipboardList))
	mux.HandleFunc("/clipboard/delete", s.requireAuth(s.handleClipboardDelete))
	mux.HandleFunc("/clipboard/clear", s.requireAuth(s.handleClipboardClear))
	mux.HandleFunc("/clipboard/pin", s.requireAuth(s.handleClipboardPin))
	mux.HandleFunc("/clipboard/sync", s.requireAuth(s.handleClipboardSync))
	mux.HandleFunc("/clipboard/push", s.requireAuth(s.handleClipboardPush))
	mux.HandleFunc("/clipboard/settings", s.requireAuth(s.handleClipboardSettings))

	// Clipboard API routes for local Electron UI
	mux.HandleFunc("/api/clipboard/add", s.handleClipboardAdd)
	mux.HandleFunc("/api/clipboard/list", s.handleClipboardList)
	mux.HandleFunc("/api/clipboard/delete", s.handleClipboardDelete)
	mux.HandleFunc("/api/clipboard/clear", s.handleClipboardClear)
	mux.HandleFunc("/api/clipboard/pin", s.handleClipboardPin)
	mux.HandleFunc("/api/clipboard/sync", s.handleClipboardSync)
	mux.HandleFunc("/api/clipboard/push", s.handleClipboardPush)
	mux.HandleFunc("/api/clipboard/settings", s.handleClipboardSettings)

	// Hotspot API routes for local Electron UI
	mux.HandleFunc("/api/hotspot/capabilities", s.handleHotspotCapabilities)
	mux.HandleFunc("/api/hotspot/start", s.handleHotspotStart)
	mux.HandleFunc("/api/hotspot/stop", s.handleHotspotStop)
	mux.HandleFunc("/api/hotspot/status", s.handleHotspotStatus)

	// Register web UI dashboard and client API
	webCtrl := web.NewWebController(cfg, pm, resolver, uploader, notifier, histMgr)
	webCtrl.SetStorage(store)
	webCtrl.SetPresenceService(presenceSvc)
	webCtrl.RegisterRoutes(mux)
	s.webCtrl = webCtrl

	pm.OnPairRequest(func(req *security.PairRequest) {
		if webCtrl != nil {
			webCtrl.BroadcastPairRequest(req)
		}
	})

	s.httpServer = &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	if cfg.EnableTLS {
		fp, err := security.EnsureCertificate(cfg.CertFile, cfg.KeyFile, cfg.DeviceName)
		if err != nil {
			return nil, fmt.Errorf("failed to configure TLS: %w", err)
		}
		s.fingerprint = fp
		webCtrl.SetFingerprint(fp)
		resolver.SetSelf(cfg.DeviceName, cfg.Port, fp)

		tlsConfig, err := security.BuildServerTLSConfig(cfg.CertFile, cfg.KeyFile)
		if err != nil {
			return nil, fmt.Errorf("failed to build TLS config: %w", err)
		}
		s.httpServer.TLSConfig = tlsConfig
	}

	return s, nil
}

// Fingerprint returns the TLS certificate SHA-256 fingerprint if TLS is enabled.
func (s *Server) Fingerprint() string {
	return s.fingerprint
}

// PairManager returns the active PairManager instance.
func (s *Server) PairManager() *security.PairManager {
	return s.pairManager
}

// Addr returns the bound address after listener is created.
func (s *Server) Addr() string {
	if s.listener != nil {
		return s.listener.Addr().String()
	}
	return s.httpServer.Addr
}

// Start listens and serves requests. It blocks until Shutdown is called or an error occurs.
func (s *Server) Start() error {
	if s.presenceService != nil {
		s.presenceService.Start()
	}

	ln, err := net.Listen("tcp", s.httpServer.Addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", s.httpServer.Addr, err)
	}
	s.listener = ln

	if s.cfg.EnableTLS && s.httpServer.TLSConfig != nil {
		tlsListener := tls.NewListener(ln, s.httpServer.TLSConfig)
		return s.httpServer.Serve(tlsListener)
	}

	return s.httpServer.Serve(ln)
}

// Shutdown gracefully stops the server.
func (s *Server) Shutdown(ctx context.Context) error {
	if s.hotspotMgr != nil {
		_ = s.hotspotMgr.StopHotspot(ctx)
	}
	if s.clipboardWatcher != nil {
		s.clipboardWatcher.Stop()
	}
	if s.presenceService != nil {
		s.presenceService.Stop()
	}
	return s.httpServer.Shutdown(ctx)
}
