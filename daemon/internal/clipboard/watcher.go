package clipboard

import (
	"crypto/sha256"
	"encoding/hex"
	"log"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

const (
	watcherPollInterval   = 1500 * time.Millisecond
	watcherInitialBackoff = 500 * time.Millisecond
	watcherMaxBackoff     = 30 * time.Second
)

// Watcher monitors the system clipboard for new content on Linux.
// It supports Wayland (via wl-paste polling) and X11 (via xclip polling).
// If neither display server is detected, Start is a no-op.
type Watcher struct {
	mu       sync.Mutex
	stopChan chan struct{}
	stopped  bool
	lastHash string
}

// NewWatcher creates a new Watcher instance.
func NewWatcher() *Watcher {
	return &Watcher{
		stopChan: make(chan struct{}),
	}
}

// IsAvailable returns true if either wl-paste or xclip is on PATH.
func IsAvailable() bool {
	if _, err := exec.LookPath("wl-paste"); err == nil {
		return true
	}
	if _, err := exec.LookPath("xclip"); err == nil {
		return true
	}
	return false
}

// Start begins clipboard monitoring. onNew is called for each new clipboard text.
// If neither $WAYLAND_DISPLAY nor $DISPLAY is set (headless), Start returns immediately.
func (w *Watcher) Start(onNew func(text string)) {
	waylandDisplay := os.Getenv("WAYLAND_DISPLAY")
	x11Display := os.Getenv("DISPLAY")

	if waylandDisplay == "" && x11Display == "" {
		log.Println("[clipboard/watcher] No display server detected; clipboard watcher disabled")
		return
	}

	if waylandDisplay != "" {
		if _, err := exec.LookPath("wl-paste"); err == nil {
			log.Println("[clipboard/watcher] Starting Wayland clipboard watcher")
			go w.runPoller(getWaylandClipboard, onNew)
			return
		}
		log.Println("[clipboard/watcher] wl-paste not found; trying X11 fallback")
	}

	if x11Display != "" {
		if _, err := exec.LookPath("xclip"); err == nil {
			log.Println("[clipboard/watcher] Starting X11 clipboard poller")
			go w.runPoller(getX11Clipboard, onNew)
			return
		}
	}

	log.Println("[clipboard/watcher] No suitable clipboard tool found; watcher disabled")
}

// Stop signals the watcher to stop.
func (w *Watcher) Stop() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.stopped {
		w.stopped = true
		close(w.stopChan)
	}
}

// runPoller polls the clipboard using the provided getter with exponential backoff on errors.
func (w *Watcher) runPoller(getter func() (string, error), onNew func(text string)) {
	backoff := watcherInitialBackoff
	consecutiveErrors := 0

	for {
		select {
		case <-w.stopChan:
			return
		default:
		}

		text, err := getter()
		if err != nil {
			consecutiveErrors++
			if consecutiveErrors == 1 {
				log.Printf("[clipboard/watcher] clipboard read error: %v", err)
			}
			w.sleepOrStop(backoff)
			backoff = minDuration(backoff*2, watcherMaxBackoff)
			continue
		}

		consecutiveErrors = 0
		backoff = watcherInitialBackoff

		w.maybeNotify(text, onNew)
		w.sleepOrStop(watcherPollInterval)
	}
}

// maybeNotify calls onNew only when text is new, non-empty, and within the size limit.
func (w *Watcher) maybeNotify(text string, onNew func(text string)) {
	// Strip trailing newline added by clipboard tools
	text = strings.TrimRight(text, "\n")
	if text == "" {
		return
	}
	if len(text) > MaxItemTextBytes {
		return
	}

	hash := hashText(text)

	w.mu.Lock()
	same := w.lastHash == hash
	if !same {
		w.lastHash = hash
	}
	w.mu.Unlock()

	if !same {
		onNew(text)
	}
}

// sleepOrStop sleeps for d or returns early if Stop has been called.
func (w *Watcher) sleepOrStop(d time.Duration) {
	select {
	case <-w.stopChan:
	case <-time.After(d):
	}
}

func getX11Clipboard() (string, error) {
	out, err := exec.Command("xclip", "-o", "-sel", "clipboard").Output()
	if err != nil {
		return "", err
	}
	return string(out), nil
}

func getWaylandClipboard() (string, error) {
	out, err := exec.Command("wl-paste", "--no-newline").Output()
	if err != nil {
		return "", err
	}
	return string(out), nil
}

func hashText(text string) string {
	h := sha256.Sum256([]byte(text))
	return hex.EncodeToString(h[:])
}

func minDuration(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}
