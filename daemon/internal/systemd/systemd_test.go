package systemd

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestGenerateUnitFile(t *testing.T) {
	binaryPath := "/usr/local/bin/skydrop-daemon"
	content := GenerateUnitFile(binaryPath)

	if !strings.Contains(content, "ExecStart=/usr/local/bin/skydrop-daemon") {
		t.Errorf("expected ExecStart to match binary path, got: %s", content)
	}
	if !strings.Contains(content, "WantedBy=default.target") {
		t.Errorf("expected WantedBy default.target")
	}
	if !strings.Contains(content, "Restart=on-failure") {
		t.Errorf("expected Restart=on-failure")
	}
}

func TestManager_InstallAndUninstall_Filesystem(t *testing.T) {
	tmpDir := t.TempDir()
	unitDir := filepath.Join(tmpDir, "systemd", "user")
	unitPath := filepath.Join(unitDir, ServiceName)

	m := &Manager{
		unitDir:  unitDir,
		unitPath: unitPath,
	}

	if m.IsInstalled() {
		t.Fatalf("expected service not installed initially")
	}

	fakeBinary := filepath.Join(tmpDir, "bin", "skydrop-daemon")
	_ = os.MkdirAll(filepath.Dir(fakeBinary), 0755)
	_ = os.WriteFile(fakeBinary, []byte("#!/bin/sh\n"), 0755)

	err := m.Install(fakeBinary)
	if err != nil {
		t.Fatalf("Install failed: %v", err)
	}

	if !m.IsInstalled() {
		t.Fatalf("expected service to be installed")
	}

	data, err := os.ReadFile(unitPath)
	if err != nil {
		t.Fatalf("failed to read installed unit file: %v", err)
	}
	if !strings.Contains(string(data), fakeBinary) {
		t.Errorf("unit file does not contain fakeBinary path: %s", string(data))
	}

	err = m.Uninstall()
	if err != nil {
		t.Fatalf("Uninstall failed: %v", err)
	}

	if m.IsInstalled() {
		t.Errorf("expected service to be uninstalled")
	}
}
