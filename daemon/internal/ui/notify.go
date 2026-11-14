package ui

import (
	_ "embed"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

//go:embed icon.png
var embeddedAppIcon []byte

// Notifier sends desktop notifications on Linux with custom icon support
// and anti-flooding batch coalescing.
type Notifier struct {
	notifySendPath string
	xdgOpenPath    string
	iconPath       string

	mu            sync.Mutex
	batchTimer    *time.Timer
	batchItems    []string
	batchLastPath string
	batchPeer     string
}

// NewNotifier detects desktop notification utilities and resolves the app icon.
func NewNotifier() *Notifier {
	notifyPath, _ := exec.LookPath("notify-send")
	openPath, _ := exec.LookPath("xdg-open")

	n := &Notifier{
		notifySendPath: notifyPath,
		xdgOpenPath:    openPath,
		iconPath:       resolveIconPath(),
	}

	return n
}

// ensureEmbeddedIcon extracts the embedded PNG logo asset to ~/.config/skydrop/logo.png
func ensureEmbeddedIcon() string {
	if len(embeddedAppIcon) == 0 {
		return ""
	}
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return ""
	}

	configDir := filepath.Join(homeDir, ".config", "skydrop")
	_ = os.MkdirAll(configDir, 0755)

	targetPath := filepath.Join(configDir, "logo.png")
	info, err := os.Stat(targetPath)
	if err != nil || info.Size() == 0 || info.Size() != int64(len(embeddedAppIcon)) {
		_ = os.WriteFile(targetPath, embeddedAppIcon, 0644)
	}
	return targetPath
}

// resolveIconPath searches for SkyDrop logo PNG assets on disk or extracts embedded icon.
func resolveIconPath() string {
	// 1. Check/extract embedded logo asset
	if embeddedPath := ensureEmbeddedIcon(); embeddedPath != "" {
		if info, err := os.Stat(embeddedPath); err == nil && info.Size() > 0 {
			return embeddedPath
		}
	}

	// 2. Search filesystem candidate locations
	homeDir, _ := os.UserHomeDir()
	execPath, _ := os.Executable()
	execDir := filepath.Dir(execPath)

	candidates := []string{
		filepath.Join(homeDir, ".config", "skydrop", "logo.png"),
		filepath.Join(homeDir, ".local", "share", "icons", "skydrop.png"),
		filepath.Join(execDir, "..", "assets", "icon.png"),
		filepath.Join(execDir, "..", "resources", "assets", "icon.png"),
		filepath.Join(execDir, "assets", "icon.png"),
		filepath.Join(execDir, "assets", "logo.png"),
		filepath.Join(execDir, "..", "desktop", "assets", "icon.png"),
		filepath.Join(execDir, "..", "desktop", "src", "ui", "assets", "logo.png"),
		filepath.Join(execDir, "..", "branding", "logo.png"),
		"/usr/share/icons/hicolor/512x512/apps/skydrop.png",
	}

	for _, candidate := range candidates {
		if abs, err := filepath.Abs(candidate); err == nil {
			if info, err := os.Stat(abs); err == nil && !info.IsDir() {
				return abs
			}
		}
	}
	return ""
}

// NotifyFileReceived queues and coalesces incoming file drop notifications
// to prevent screen flooding when multiple files arrive in batch.
func (n *Notifier) NotifyFileReceived(filename, savedPath, peerName string) error {
	if n.notifySendPath == "" {
		log.Printf("[SkyDrop Notification] File Received: %s (saved: %s)", filename, savedPath)
		return nil
	}

	n.mu.Lock()
	defer n.mu.Unlock()

	n.batchItems = append(n.batchItems, filename)
	n.batchLastPath = savedPath
	if peerName != "" {
		n.batchPeer = peerName
	}

	if n.batchTimer != nil {
		n.batchTimer.Stop()
	}

	// Debounce batch notifications by 1.2s to combine multiple files into a single card
	n.batchTimer = time.AfterFunc(1200*time.Millisecond, func() {
		n.flushBatch()
	})

	return nil
}

func (n *Notifier) flushBatch() {
	n.mu.Lock()
	items := append([]string(nil), n.batchItems...)
	peerName := n.batchPeer
	n.batchItems = nil
	n.batchPeer = ""
	n.batchTimer = nil
	n.mu.Unlock()

	if len(items) == 0 {
		return
	}

	var title, body string
	if len(items) == 1 {
		title = "SkyDrop: File Received"
		if peerName != "" {
			body = fmt.Sprintf("%s received from %s", items[0], peerName)
		} else {
			body = fmt.Sprintf("%s was saved to your downloads", items[0])
		}
	} else if len(items) == 2 {
		title = "SkyDrop: 2 Files Received"
		if peerName != "" {
			body = fmt.Sprintf("%s and %s received from %s", items[0], items[1], peerName)
		} else {
			body = fmt.Sprintf("%s and %s saved to your downloads", items[0], items[1])
		}
	} else {
		title = fmt.Sprintf("SkyDrop: %d Files Received", len(items))
		if peerName != "" {
			body = fmt.Sprintf("%s and %d other files received from %s", items[0], len(items)-1, peerName)
		} else {
			body = fmt.Sprintf("%s and %d other files saved to downloads", items[0], len(items)-1)
		}
	}

	icon := n.iconPath
	if icon == "" {
		icon = "document-save"
	}

	// -h string:x-canonical-private-synchronous:skydrop-file-received replaces previous notification
	// toasts in-place so multiple drops don't stack and flood the desktop screen.
	cmd := exec.Command(n.notifySendPath,
		"--app-name=SkyDrop",
		"-h", "string:x-canonical-private-synchronous:skydrop-file-received",
		"-h", "string:desktop-entry:skydrop-desktop",
		"-i", icon,
		"--urgency=normal",
		title,
		body,
	)

	if err := cmd.Run(); err != nil {
		log.Printf("[SkyDrop Notification Error] %v", err)
	}
}

// NotifyTextReceived displays a notification when text or link is received.
func (n *Notifier) NotifyTextReceived(text, peerName string) error {
	title := "SkyDrop: Text Received"
	body := strings.TrimSpace(text)
	if len(body) > 100 {
		body = body[:97] + "..."
	}
	if peerName != "" {
		title = fmt.Sprintf("SkyDrop: Text from %s", peerName)
	}

	if n.notifySendPath == "" {
		log.Printf("[SkyDrop Notification] %s - %s", title, body)
		return nil
	}

	icon := n.iconPath
	if icon == "" {
		icon = "dialog-information"
	}

	cmd := exec.Command(n.notifySendPath,
		"--app-name=SkyDrop",
		"-h", "string:x-canonical-private-synchronous:skydrop-text-received",
		"-h", "string:desktop-entry:skydrop-desktop",
		"-i", icon,
		"--urgency=normal",
		title,
		body,
	)

	return cmd.Run()
}

// OpenPath opens the file or directory using the default Linux desktop application.
func (n *Notifier) OpenPath(targetPath string) error {
	if n.xdgOpenPath == "" {
		return fmt.Errorf("xdg-open utility not found in PATH")
	}

	cmd := exec.Command(n.xdgOpenPath, targetPath)
	return cmd.Start()
}
