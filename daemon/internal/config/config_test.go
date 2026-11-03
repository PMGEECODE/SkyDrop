package config

import (
	"os"
	"testing"
)

func TestLoadConfig_Defaults(t *testing.T) {
	// Clear any environment overrides
	os.Unsetenv("SKYDROP_PORT")
	os.Unsetenv("SKYDROP_DEVICE_NAME")
	os.Unsetenv("SKYDROP_STORAGE_DIR")
	os.Unsetenv("SKYDROP_MAX_FILE_SIZE")
	os.Unsetenv("SKYDROP_ENABLE_TLS")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("expected no error loading default config, got: %v", err)
	}

	if cfg.Port != DefaultPort {
		t.Errorf("expected port %d, got %d", DefaultPort, cfg.Port)
	}
	if cfg.MaxFileSize != DefaultMaxFileSize {
		t.Errorf("expected max file size %d, got %d", DefaultMaxFileSize, cfg.MaxFileSize)
	}
	if !cfg.EnableTLS {
		t.Errorf("expected TLS to be enabled by default")
	}
	if cfg.ServiceName != DefaultServiceName {
		t.Errorf("expected service name %s, got %s", DefaultServiceName, cfg.ServiceName)
	}
}

func TestLoadConfig_EnvOverrides(t *testing.T) {
	t.Setenv("SKYDROP_PORT", "9000")
	t.Setenv("SKYDROP_DEVICE_NAME", "Test-Laptop")
	t.Setenv("SKYDROP_STORAGE_DIR", "/tmp/skydrop-test")
	t.Setenv("SKYDROP_MAX_FILE_SIZE", "52428800")
	t.Setenv("SKYDROP_ENABLE_TLS", "false")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("expected no error loading overridden config, got: %v", err)
	}

	if cfg.Port != 9000 {
		t.Errorf("expected port 9000, got %d", cfg.Port)
	}
	if cfg.DeviceName != "Test-Laptop" {
		t.Errorf("expected device name Test-Laptop, got %s", cfg.DeviceName)
	}
	if cfg.StorageDir != "/tmp/skydrop-test" {
		t.Errorf("expected storage dir /tmp/skydrop-test, got %s", cfg.StorageDir)
	}
	if cfg.MaxFileSize != 52428800 {
		t.Errorf("expected max file size 52428800, got %d", cfg.MaxFileSize)
	}
	if cfg.EnableTLS {
		t.Errorf("expected TLS to be disabled")
	}
}

func TestConfig_Validate(t *testing.T) {
	tests := []struct {
		name    string
		cfg     Config
		wantErr bool
	}{
		{
			name: "valid config",
			cfg: Config{
				Port:        8443,
				DeviceName:  "Device",
				StorageDir:  "/path",
				MaxFileSize: 1024,
			},
			wantErr: false,
		},
		{
			name: "invalid port zero",
			cfg: Config{
				Port:        0,
				DeviceName:  "Device",
				StorageDir:  "/path",
				MaxFileSize: 1024,
			},
			wantErr: true,
		},
		{
			name: "invalid port too high",
			cfg: Config{
				Port:        70000,
				DeviceName:  "Device",
				StorageDir:  "/path",
				MaxFileSize: 1024,
			},
			wantErr: true,
		},
		{
			name: "empty device name",
			cfg: Config{
				Port:        8443,
				DeviceName:  "",
				StorageDir:  "/path",
				MaxFileSize: 1024,
			},
			wantErr: true,
		},
		{
			name: "empty storage dir",
			cfg: Config{
				Port:        8443,
				DeviceName:  "Device",
				StorageDir:  "",
				MaxFileSize: 1024,
			},
			wantErr: true,
		},
		{
			name: "negative max file size",
			cfg: Config{
				Port:        8443,
				DeviceName:  "Device",
				StorageDir:  "/path",
				MaxFileSize: -1,
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.cfg.Validate()
			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr = %v", err, tt.wantErr)
			}
		})
	}
}
