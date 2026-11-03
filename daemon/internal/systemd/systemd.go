package systemd

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const (
	ServiceName = "skydrop.service"
)

// Manager handles installation, removal, and control of the systemd user service.
type Manager struct {
	unitDir string
	unitPath string
}

// NewManager creates a Manager targeting the current user's systemd directory.
func NewManager() (*Manager, error) {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return nil, fmt.Errorf("failed to get user home directory: %w", err)
	}

	unitDir := filepath.Join(homeDir, ".config", "systemd", "user")
	return &Manager{
		unitDir:  unitDir,
		unitPath: filepath.Join(unitDir, ServiceName),
	}, nil
}

// GenerateUnitFile generates the systemd user unit content for the given binary path.
func GenerateUnitFile(binaryPath string) string {
	return fmt.Sprintf(`[Unit]
Description=SkyDrop Local File Drop Daemon
After=network.target

[Service]
Type=simple
ExecStart=%s
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=default.target
`, binaryPath)
}

// IsInstalled returns true if the service unit file exists on disk.
func (m *Manager) IsInstalled() bool {
	_, err := os.Stat(m.unitPath)
	return err == nil
}

// Install writes the unit file and reloads systemd user daemon.
func (m *Manager) Install(binaryPath string) error {
	if binaryPath == "" {
		execPath, err := os.Executable()
		if err != nil {
			return fmt.Errorf("failed to determine executable path: %w", err)
		}
		// If running from skydrop CLI, resolve daemon binary in the same directory
		dir := filepath.Dir(execPath)
		candidate := filepath.Join(dir, "skydrop-daemon")
		if _, err := os.Stat(candidate); err == nil {
			binaryPath = candidate
		} else {
			binaryPath = execPath
		}
	}

	absBinary, err := filepath.Abs(binaryPath)
	if err != nil {
		return fmt.Errorf("failed to resolve absolute path for binary: %w", err)
	}

	if err := os.MkdirAll(m.unitDir, 0755); err != nil {
		return fmt.Errorf("failed to create systemd user directory: %w", err)
	}

	content := GenerateUnitFile(absBinary)
	if err := os.WriteFile(m.unitPath, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write service file %s: %w", m.unitPath, err)
	}

	// Reload systemd user daemon if systemctl is available
	_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
	return nil
}

// Uninstall stops, disables, and removes the service unit file.
func (m *Manager) Uninstall() error {
	_ = exec.Command("systemctl", "--user", "stop", ServiceName).Run()
	_ = exec.Command("systemctl", "--user", "disable", ServiceName).Run()

	if err := os.Remove(m.unitPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to remove service unit file: %w", err)
	}

	_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
	return nil
}

// Enable enables and immediately starts the service.
func (m *Manager) Enable() error {
	cmd := exec.Command("systemctl", "--user", "enable", "--now", ServiceName)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl enable failed: %s (%w)", strings.TrimSpace(string(output)), err)
	}
	return nil
}

// Disable disables the service.
func (m *Manager) Disable() error {
	cmd := exec.Command("systemctl", "--user", "disable", ServiceName)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl disable failed: %s (%w)", strings.TrimSpace(string(output)), err)
	}
	return nil
}

// Start starts the systemd service.
func (m *Manager) Start() error {
	cmd := exec.Command("systemctl", "--user", "start", ServiceName)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl start failed: %s (%w)", strings.TrimSpace(string(output)), err)
	}
	return nil
}

// Stop stops the systemd service.
func (m *Manager) Stop() error {
	cmd := exec.Command("systemctl", "--user", "stop", ServiceName)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl stop failed: %s (%w)", strings.TrimSpace(string(output)), err)
	}
	return nil
}

// Status returns the current status output from systemctl.
func (m *Manager) Status() (string, error) {
	if !m.IsInstalled() {
		return "", errors.New("service is not installed")
	}
	cmd := exec.Command("systemctl", "--user", "status", ServiceName)
	output, err := cmd.CombinedOutput()
	// systemctl status returns non-zero when inactive or stopped, but still outputs status
	return strings.TrimSpace(string(output)), err
}
