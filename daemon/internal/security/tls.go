package security

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// EnsureCertificate verifies that the certificate and key exist, or generates self-signed ECDSA ones.
// Returns the SHA-256 fingerprint formatted in uppercase hex with colons.
func EnsureCertificate(certPath, keyPath, deviceName string) (string, error) {
	if _, err := os.Stat(certPath); errors.Is(err, os.ErrNotExist) {
		if err := GenerateSelfSignedCert(certPath, keyPath, deviceName); err != nil {
			return "", fmt.Errorf("failed to generate self-signed certificate: %w", err)
		}
	} else if _, err := os.Stat(keyPath); errors.Is(err, os.ErrNotExist) {
		// Key missing while cert exists, re-generate both
		if err := GenerateSelfSignedCert(certPath, keyPath, deviceName); err != nil {
			return "", fmt.Errorf("failed to regenerate certificate pair: %w", err)
		}
	}

	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return "", fmt.Errorf("failed to read certificate file: %w", err)
	}

	fingerprint, err := ComputeCertificateFingerprint(certPEM)
	if err != nil {
		return "", fmt.Errorf("failed to compute certificate fingerprint: %w", err)
	}

	return fingerprint, nil
}

// GenerateSelfSignedCert generates an ECDSA P-256 certificate valid for 10 years and writes it to disk.
func GenerateSelfSignedCert(certPath, keyPath, deviceName string) error {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("failed to generate ecdsa key: %w", err)
	}

	serialNumberLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	serialNumber, err := rand.Int(rand.Reader, serialNumberLimit)
	if err != nil {
		return fmt.Errorf("failed to generate serial number: %w", err)
	}

	notBefore := time.Now().Add(-1 * time.Hour)
	notAfter := notBefore.Add(10 * 365 * 24 * time.Hour)

	template := x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{"SkyDrop"},
			CommonName:   deviceName,
		},
		NotBefore:             notBefore,
		NotAfter:              notAfter,
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
	}

	// Add local SANs
	template.IPAddresses = []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")}
	template.DNSNames = []string{"localhost", deviceName}

	derBytes, err := x509.CreateCertificate(rand.Reader, &template, &template, &priv.PublicKey, priv)
	if err != nil {
		return fmt.Errorf("failed to create certificate: %w", err)
	}

	// Ensure parent directory exists
	if err := os.MkdirAll(filepath.Dir(certPath), 0700); err != nil {
		return fmt.Errorf("failed to create cert directory: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(keyPath), 0700); err != nil {
		return fmt.Errorf("failed to create key directory: %w", err)
	}

	// Write cert PEM
	certFile, err := os.OpenFile(certPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
	if err != nil {
		return fmt.Errorf("failed to open cert file for writing: %w", err)
	}
	defer certFile.Close()

	if err := pem.Encode(certFile, &pem.Block{Type: "CERTIFICATE", Bytes: derBytes}); err != nil {
		return fmt.Errorf("failed to encode cert PEM: %w", err)
	}

	// Write key PEM
	keyBytes, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return fmt.Errorf("failed to marshal EC private key: %w", err)
	}

	keyFile, err := os.OpenFile(keyPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return fmt.Errorf("failed to open key file for writing: %w", err)
	}
	defer keyFile.Close()

	if err := pem.Encode(keyFile, &pem.Block{Type: "EC PRIVATE KEY", Bytes: keyBytes}); err != nil {
		return fmt.Errorf("failed to encode key PEM: %w", err)
	}

	return nil
}

// ComputeCertificateFingerprint parses a PEM certificate and returns its SHA-256 colon-separated hex fingerprint.
func ComputeCertificateFingerprint(certPEM []byte) (string, error) {
	block, _ := pem.Decode(certPEM)
	if block == nil || block.Type != "CERTIFICATE" {
		return "", errors.New("failed to decode certificate PEM")
	}

	hash := sha256.Sum256(block.Bytes)
	hexStr := hex.EncodeToString(hash[:])

	var parts []string
	for i := 0; i < len(hexStr); i += 2 {
		parts = append(parts, strings.ToUpper(hexStr[i:i+2]))
	}

	return strings.Join(parts, ":"), nil
}

// BuildServerTLSConfig builds modern, secure TLS server configuration.
func BuildServerTLSConfig(certPath, keyPath string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, fmt.Errorf("failed to load x509 key pair: %w", err)
	}

	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
		CurvePreferences: []tls.CurveID{
			tls.X25519,
			tls.CurveP256,
		},
	}, nil
}
