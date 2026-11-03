package ui

import (
	"fmt"
	"log"
	"os/exec"
)

// Notifier sends desktop notifications on Linux.
type Notifier struct {
	notifySendPath string
	xdgOpenPath    string
}

// NewNotifier detects desktop notification utilities on the host.
func NewNotifier() *Notifier {
	notifyPath, _ := exec.LookPath("notify-send")
	openPath, _ := exec.LookPath("xdg-open")

	return &Notifier{
		notifySendPath: notifyPath,
		xdgOpenPath:    openPath,
	}
}

// NotifyFileReceived displays a notification indicating a file was received from a peer.
func (n *Notifier) NotifyFileReceived(filename, savedPath, peerName string) error {
	title := "SkyDrop: File Received"
	body := fmt.Sprintf("%s was saved to your downloads", filename)
	if peerName != "" {
		body = fmt.Sprintf("%s received from %s", filename, peerName)
	}

	if n.notifySendPath == "" {
		log.Printf("[SkyDrop Notification] %s - %s (saved: %s)", title, body, savedPath)
		return nil
	}

	cmd := exec.Command(n.notifySendPath,
		"--app-name=SkyDrop",
		"--icon=document-save",
		"--urgency=normal",
		title,
		body,
	)

	return cmd.Run()
}

// NotifyTextReceived displays a notification when text or link is received.
func (n *Notifier) NotifyTextReceived(text, peerName string) error {
	title := "SkyDrop: Text Received"
	body := text
	if peerName != "" {
		title = fmt.Sprintf("SkyDrop: Text from %s", peerName)
	}

	if n.notifySendPath == "" {
		log.Printf("[SkyDrop Notification] %s - %s", title, body)
		return nil
	}

	cmd := exec.Command(n.notifySendPath,
		"--app-name=SkyDrop",
		"--icon=dialog-information",
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
