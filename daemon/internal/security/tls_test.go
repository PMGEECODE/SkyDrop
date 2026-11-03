package security

import (
	"crypto/tls"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEnsureCertificateAndFingerprint(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "skydrop-tls-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	certPath := filepath.Join(tmpDir, "cert.pem")
	keyPath := filepath.Join(tmpDir, "key.pem")

	// 1. Generation on first call
	fp1, err := EnsureCertificate(certPath, keyPath, "TestLaptop")
	if err != nil {
		t.Fatalf("EnsureCertificate failed: %v", err)
	}

	if fp1 == "" {
		t.Fatal("expected non-empty fingerprint")
	}

	parts := strings.Split(fp1, ":")
	if len(parts) != 32 {
		t.Fatalf("expected 32 hex segments for SHA-256 fingerprint, got %d (%s)", len(parts), fp1)
	}

	// Verify files created with right permissions
	certInfo, err := os.Stat(certPath)
	if err != nil {
		t.Fatalf("cert file missing: %v", err)
	}
	if certInfo.Mode().Perm() != 0644 {
		t.Errorf("expected 0644 for cert, got %v", certInfo.Mode().Perm())
	}

	keyInfo, err := os.Stat(keyPath)
	if err != nil {
		t.Fatalf("key file missing: %v", err)
	}
	if keyInfo.Mode().Perm() != 0600 {
		t.Errorf("expected 0600 for key, got %v", keyInfo.Mode().Perm())
	}

	// 2. Second call should return the exact same fingerprint without regenerating
	fp2, err := EnsureCertificate(certPath, keyPath, "TestLaptop")
	if err != nil {
		t.Fatalf("second call to EnsureCertificate failed: %v", err)
	}
	if fp1 != fp2 {
		t.Fatalf("fingerprint changed between calls: %s != %s", fp1, fp2)
	}

	// 3. Test BuildServerTLSConfig
	tlsCfg, err := BuildServerTLSConfig(certPath, keyPath)
	if err != nil {
		t.Fatalf("BuildServerTLSConfig failed: %v", err)
	}
	if tlsCfg.MinVersion != tls.VersionTLS12 {
		t.Errorf("expected min TLS version 1.2, got %x", tlsCfg.MinVersion)
	}
	if len(tlsCfg.Certificates) != 1 {
		t.Fatalf("expected 1 certificate in tls.Config, got %d", len(tlsCfg.Certificates))
	}
}
