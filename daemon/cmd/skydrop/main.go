package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/skip2/go-qrcode"

	"skydrop/daemon/internal/client"
	"skydrop/daemon/internal/config"
	"skydrop/daemon/internal/discovery"
	"skydrop/daemon/internal/history"
	"skydrop/daemon/internal/security"
	"skydrop/daemon/internal/systemd"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	command := os.Args[1]
	switch command {
	case "send":
		if len(os.Args) < 3 {
			fmt.Println("Usage: skydrop send <file-path-1> [file-path-2 ...]")
			os.Exit(1)
		}
		handleSend(os.Args[2:])
	case "text":
		if len(os.Args) < 3 {
			fmt.Println("Usage: skydrop text <message-or-url>")
			os.Exit(1)
		}
		handleText(os.Args[2])
	case "list":
		handleList()
	case "open":
		handleOpen()
	case "service":
		handleService(os.Args[2:])
	case "history":
		handleHistory(os.Args[2:])
	case "pair":
		handlePairCLI()
	default:
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println("SkyDrop - Linux to Android Local File Drop CLI")
	fmt.Println("\nCommands:")
	fmt.Println("  skydrop send <file...>  Send one or multiple files to discovered Android peer")
	fmt.Println("  skydrop text <text>     Share text or URL with peer")
	fmt.Println("  skydrop list            Discover and list nearby peers")
	fmt.Println("  skydrop pair            Generate 6-digit PIN and QR code to pair phone")
	fmt.Println("  skydrop open            Open web control panel in browser")
	fmt.Println("  skydrop history [clear] View or clear transfer history")
	fmt.Println("  skydrop service <cmd>   Manage systemd user service (install, status, start, stop, uninstall)")
}

func handleSend(filePaths []string) {
	var validPaths []string
	for _, fp := range filePaths {
		absPath, err := filepath.Abs(fp)
		if err != nil {
			fmt.Printf("⚠️  Error resolving file %s: %v\n", fp, err)
			continue
		}
		if stat, err := os.Stat(absPath); err != nil || stat.IsDir() {
			fmt.Printf("⚠️  Skipping invalid file: %s\n", absPath)
			continue
		}
		validPaths = append(validPaths, absPath)
	}

	if len(validPaths) == 0 {
		fmt.Println("❌ No valid files to send.")
		os.Exit(1)
	}

	fmt.Println("⚡ Scanning local network for SkyDrop peers...")
	peer, token := findTargetPeer()
	if peer == nil {
		fmt.Println("❌ No SkyDrop peers found. Make sure receiver mode is active on phone.")
		os.Exit(1)
	}

	fmt.Printf("📤 Uploading %d file(s) to %s (%s)...\n", len(validPaths), peer.Name, peer.BaseURL())

	uploader := client.NewUploader(true)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Minute)
	defer cancel()

	var lastPercent int
	uploaded, err := uploader.UploadFiles(ctx, peer.BaseURL(), validPaths, token, func(idx, total int, filename string, written, sz int64, percent int) {
		if percent != lastPercent && percent%20 == 0 {
			lastPercent = percent
			fmt.Printf("   [%d/%d] %s: %d%%\n", idx, total, filename, percent)
		}
	})

	if err != nil {
		fmt.Printf("❌ Upload failed: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("✅ Successfully transferred %d file(s)!\n", len(uploaded))
}

func handleText(text string) {
	fmt.Println("⚡ Scanning local network for SkyDrop peers...")
	peer, token := findTargetPeer()
	if peer == nil {
		fmt.Println("❌ No SkyDrop peers found. Make sure receiver mode is active on phone.")
		os.Exit(1)
	}

	uploader := client.NewUploader(true)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	err := uploader.SendText(ctx, peer.BaseURL(), text, token)
	if err != nil {
		fmt.Printf("❌ Failed to send text: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("✅ Text sent to %s successfully!\n", peer.Name)
}

func handleList() {
	fmt.Println("⚡ Scanning for nearby devices (mDNS)...")
	cfg, _ := config.LoadConfig()
	resolver := discovery.NewPeerResolver()
	if cfg != nil {
		resolver.SetSelf(cfg.DeviceName, cfg.Port, "")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	peers, _ := resolver.DiscoverPeers(ctx, 1500*time.Millisecond)
	if len(peers) == 0 {
		fmt.Println("No peers found on local network.")
		return
	}

	fmt.Printf("Found %d peer(s):\n", len(peers))
	for _, p := range peers {
		fmt.Printf(" • %s (%s) - %s\n", p.Name, p.BaseURL(), p.OS)
	}
}

func handleOpen() {
	cfg, err := config.LoadConfig()
	port := config.DefaultPort
	if err == nil {
		port = cfg.Port
	}
	url := fmt.Sprintf("http://localhost:%d", port)
	fmt.Printf("Opening %s in browser...\n", url)
	_ = exec.Command("xdg-open", url).Start()
}

func handleHistory(args []string) {
	cfg, err := config.LoadConfig()
	if err != nil {
		fmt.Printf("Error loading config: %v\n", err)
		os.Exit(1)
	}

	mgr, err := history.NewManager(cfg.HistoryFile, 100)
	if err != nil {
		fmt.Printf("Error accessing history: %v\n", err)
		os.Exit(1)
	}

	if len(args) > 0 && args[0] == "clear" {
		if err := mgr.Clear(); err != nil {
			fmt.Printf("❌ Failed to clear history: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("✅ Transfer history cleared.")
		return
	}

	entries := mgr.ListEntries(50)
	if len(entries) == 0 {
		fmt.Println("No transfer history found.")
		return
	}

	fmt.Printf("📜 Transfer History (%d items):\n", len(entries))
	for _, e := range entries {
		timeStr := e.Timestamp.Local().Format("2006-01-02 15:04:05")
		dirSymbol := "⬇️ In"
		if e.Direction == history.DirectionOutbound {
			dirSymbol = "⬆️ Out"
		}
		sizeStr := formatBytes(e.Size)
		statusBadge := "✅"
		if e.Status == history.StatusFailed {
			statusBadge = "❌"
		}
		fmt.Printf(" %s %s [%s] %s (%s) %s\n", statusBadge, dirSymbol, timeStr, e.Filename, sizeStr, e.PeerName)
	}
}

func handleService(args []string) {
	if len(args) == 0 {
		fmt.Println("Usage: skydrop service <install|uninstall|enable|disable|start|stop|status>")
		os.Exit(1)
	}

	mgr, err := systemd.NewManager()
	if err != nil {
		fmt.Printf("Error initializing systemd manager: %v\n", err)
		os.Exit(1)
	}

	switch args[0] {
	case "install":
		binaryPath := ""
		if len(args) > 1 {
			binaryPath = args[1]
		}
		if err := mgr.Install(binaryPath); err != nil {
			fmt.Printf("❌ Failed to install service: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("✅ Systemd user service installed successfully.")
		fmt.Println("Run 'skydrop service enable' to start it automatically on boot.")
	case "uninstall":
		if err := mgr.Uninstall(); err != nil {
			fmt.Printf("❌ Failed to uninstall service: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("✅ Systemd user service removed.")
	case "enable":
		if err := mgr.Enable(); err != nil {
			fmt.Printf("❌ Failed to enable service: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("✅ Service enabled and started.")
	case "disable":
		if err := mgr.Disable(); err != nil {
			fmt.Printf("❌ Failed to disable service: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("✅ Service disabled.")
	case "start":
		if err := mgr.Start(); err != nil {
			fmt.Printf("❌ Failed to start service: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("✅ Service started.")
	case "stop":
		if err := mgr.Stop(); err != nil {
			fmt.Printf("❌ Failed to stop service: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("✅ Service stopped.")
	case "status":
		status, err := mgr.Status()
		if status != "" {
			fmt.Println(status)
		} else if err != nil {
			fmt.Printf("❌ %v\n", err)
		}
	default:
		fmt.Printf("Unknown service action: %s\n", args[0])
		os.Exit(1)
	}
}

func findTargetPeer() (*discovery.DiscoveredPeer, string) {
	cfg, _ := config.LoadConfig()
	var token string
	if cfg != nil {
		pm, _ := security.NewPairManager(cfg.PairedDevicesFile)
		if pm != nil {
			peers := pm.ListPeers()
			if len(peers) > 0 {
				token = peers[0].AuthToken
			}
		}
	}

	resolver := discovery.NewPeerResolver()
	if cfg != nil {
		resolver.SetSelf(cfg.DeviceName, cfg.Port, "")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2500*time.Millisecond)
	defer cancel()

	peers, err := resolver.DiscoverPeers(ctx, 2*time.Second)
	if err != nil || len(peers) == 0 {
		return nil, ""
	}

	return &peers[0], token
}

func formatBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(b)/float64(div), "KMGTPE"[exp])
}

func handlePairCLI() {
	cfg, err := config.LoadConfig()
	if err != nil {
		fmt.Printf("Config error: %v\n", err)
		os.Exit(1)
	}

	proto := "http"
	if cfg.EnableTLS {
		proto = "https"
	}
	endpoint := fmt.Sprintf("%s://127.0.0.1:%d/pair/start", proto, cfg.Port)

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}
	httpClient := &http.Client{Transport: tr, Timeout: 5 * time.Second}

	resp, err := httpClient.Post(endpoint, "application/json", nil)
	if err != nil {
		fmt.Printf("❌ Failed to contact SkyDrop daemon at %s: %v\nMake sure the daemon is running ('skydrop service start' or 'skydrop-daemon').\n", endpoint, err)
		os.Exit(1)
	}
	defer resp.Body.Close()

	var res struct {
		Secret     string `json:"secret"`
		PairingURI string `json:"pairing_uri"`
		ExpiresAt  string `json:"expires_at"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		fmt.Printf("❌ Failed to parse response: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("==================================================")
	fmt.Println("⚡ SkyDrop Pairing PIN & QR")
	fmt.Println("==================================================")
	fmt.Printf("6-Digit PIN: %s (Expires: %s)\n", res.Secret, res.ExpiresAt)
	fmt.Printf("URI: %s\n\n", res.PairingURI)

	if qr, err := qrcode.New(res.PairingURI, qrcode.Medium); err == nil {
		fmt.Println("Scan this QR code with the SkyDrop Android app:")
		fmt.Println(qr.ToSmallString(false))
	}
}
