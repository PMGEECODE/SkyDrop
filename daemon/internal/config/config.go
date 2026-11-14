package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
)

const (
	DefaultPort         = 8443
	DefaultServiceName  = "_filedrop._tcp"
	DefaultMaxFileSize  = 100 * 1024 * 1024 * 1024 // 100 GiB max file size
	DefaultDeviceName   = "SkyDrop Linux"
	DefaultConfigDir    = ".skydrop"
	DefaultDownloadsDir = "Downloads/SkyDrop"
)

// Config represents the daemon runtime configuration.
type Config struct {
	Port           int
	DeviceName     string
	ServiceName    string
	StorageDir     string
	CertFile       string
	KeyFile        string
	MaxFileSize       int64
	EnableTLS         bool
	PairedDevicesFile string
	HistoryFile       string
	ClipboardFile         string
	ClipboardSettingsFile string
	RequirePairing    bool
	DiscoveryTXT      []string
}

// LoadConfig loads configuration from environment variables with safe defaults.
func LoadConfig() (*Config, error) {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return nil, fmt.Errorf("failed to resolve user home directory: %w", err)
	}

	skydropDir := filepath.Join(homeDir, DefaultConfigDir)
	storageDir := os.Getenv("SKYDROP_STORAGE_DIR")
	if storageDir == "" {
		storageDir = filepath.Join(homeDir, DefaultDownloadsDir)
	}

	port := DefaultPort
	if portStr := os.Getenv("SKYDROP_PORT"); portStr != "" {
		parsedPort, err := strconv.Atoi(portStr)
		if err != nil || parsedPort < 1 || parsedPort > 65535 {
			return nil, fmt.Errorf("invalid SKYDROP_PORT: %s", portStr)
		}
		port = parsedPort
	}

	deviceName := os.Getenv("SKYDROP_DEVICE_NAME")
	if deviceName == "" {
		hostname, err := os.Hostname()
		if err == nil && hostname != "" {
			deviceName = hostname
		} else {
			deviceName = DefaultDeviceName
		}
	}

	certFile := os.Getenv("SKYDROP_CERT_FILE")
	if certFile == "" {
		certFile = filepath.Join(skydropDir, "cert.pem")
	}

	keyFile := os.Getenv("SKYDROP_KEY_FILE")
	if keyFile == "" {
		keyFile = filepath.Join(skydropDir, "key.pem")
	}

	maxFileSize := int64(DefaultMaxFileSize)
	if maxFileStr := os.Getenv("SKYDROP_MAX_FILE_SIZE"); maxFileStr != "" {
		parsedMax, err := strconv.ParseInt(maxFileStr, 10, 64)
		if err != nil || parsedMax <= 0 {
			return nil, fmt.Errorf("invalid SKYDROP_MAX_FILE_SIZE: %s", maxFileStr)
		}
		maxFileSize = parsedMax
	}

	enableTLS := true
	if tlsStr := os.Getenv("SKYDROP_ENABLE_TLS"); tlsStr != "" {
		enableTLS = tlsStr == "true" || tlsStr == "1"
	}

	pairedDevicesFile := os.Getenv("SKYDROP_PAIRED_DEVICES_FILE")
	if pairedDevicesFile == "" {
		pairedDevicesFile = filepath.Join(skydropDir, "paired_devices.json")
	}

	historyFile := os.Getenv("SKYDROP_HISTORY_FILE")
	if historyFile == "" {
		historyFile = filepath.Join(skydropDir, "history.json")
	}

	clipboardFile := os.Getenv("SKYDROP_CLIPBOARD_FILE")
	if clipboardFile == "" {
		clipboardFile = filepath.Join(skydropDir, "clipboard.json")
	}

	clipboardSettingsFile := os.Getenv("SKYDROP_CLIPBOARD_SETTINGS_FILE")
	if clipboardSettingsFile == "" {
		clipboardSettingsFile = filepath.Join(skydropDir, "clipboard_settings.json")
	}

	requirePairing := true
	if pairStr := os.Getenv("SKYDROP_REQUIRE_PAIRING"); pairStr != "" {
		requirePairing = pairStr == "true" || pairStr == "1"
	}

	cfg := &Config{
		Port:              port,
		DeviceName:        deviceName,
		ServiceName:       DefaultServiceName,
		StorageDir:        storageDir,
		CertFile:          certFile,
		KeyFile:           keyFile,
		MaxFileSize:       maxFileSize,
		EnableTLS:         enableTLS,
		PairedDevicesFile: pairedDevicesFile,
		HistoryFile:           historyFile,
		ClipboardFile:         clipboardFile,
		ClipboardSettingsFile: clipboardSettingsFile,
		RequirePairing:        requirePairing,
		DiscoveryTXT:      []string{"version=1.0.0", fmt.Sprintf("device=%s", deviceName)},
	}

	if err := cfg.Validate(); err != nil {
		return nil, err
	}

	return cfg, nil
}

// Validate ensures configuration fields meet all system requirements.
func (c *Config) Validate() error {
	if c.Port < 1 || c.Port > 65535 {
		return errors.New("port must be between 1 and 65535")
	}
	if c.DeviceName == "" {
		return errors.New("device name cannot be empty")
	}
	if c.StorageDir == "" {
		return errors.New("storage directory cannot be empty")
	}
	if c.MaxFileSize <= 0 {
		return errors.New("max file size must be greater than zero")
	}
	return nil
}
