package presence

import (
	"os"
	"time"
)

const (
	ProtocolVersion = "skydrop/v1"
)

var (
	AppVersion = "1.0.0"
)

func init() {
	if envVer := os.Getenv("SKYDROP_VERSION"); envVer != "" {
		AppVersion = envVer
	}
}

const (
	CapHealthV1        = "health_v1"
	CapResumableUpload = "resumable_upload"
	CapChecksumV1      = "checksum_v1"
	CapBatchTransfer   = "batch_transfer"

	ErrCodeAuthFailed      = "ERR_AUTH_FAILED"
	ErrCodeNotPaired       = "ERR_NOT_PAIRED"
	ErrCodeTokenRevoked    = "ERR_TOKEN_REVOKED"
	ErrCodeCertMismatch    = "ERR_CERT_MISMATCH"
	ErrCodeVersionMismatch = "ERR_VERSION_MISMATCH"
	ErrCodeTimeout         = "ERR_TIMEOUT"
	ErrCodeUnreachable     = "ERR_UNREACHABLE"
	ErrCodeConnReset       = "ERR_CONN_RESET"
	ErrCodeUnknown         = "ERR_UNKNOWN"

	StateUnknown  PresenceState = "UNKNOWN"
	StateChecking PresenceState = "CHECKING"
	StateOnline   PresenceState = "ONLINE"
	StateBusy     PresenceState = "BUSY"
	StateDegraded PresenceState = "DEGRADED"
	StateOffline  PresenceState = "OFFLINE"
	StateError    PresenceState = "ERROR"
)

var DefaultCapabilities = []string{
	CapHealthV1,
	CapResumableUpload,
	CapChecksumV1,
	CapBatchTransfer,
}

// PresenceState models the live connection state of a remote peer.
type PresenceState string

// HealthResponse is returned by the GET /health endpoint.
type HealthResponse struct {
	DeviceID        string   `json:"device_id"`
	DeviceName      string   `json:"device_name"`
	AppVersion      string   `json:"app_version"`
	ProtocolVersion string   `json:"protocol_version"`
	Capabilities    []string `json:"capabilities"`
	State           string   `json:"state"` // "idle" | "transferring"
	Fingerprint     string   `json:"fingerprint,omitempty"`
}

// LeaveRequest is submitted to POST /presence/leave for graceful disconnection.
type LeaveRequest struct {
	DeviceID string `json:"device_id"`
	Reason   string `json:"reason,omitempty"`
}

// PeerPresence describes the active presence of a remote peer.
type PeerPresence struct {
	PeerID          string        `json:"peer_id"`
	DeviceName      string        `json:"device_name"`
	State           PresenceState `json:"state"`
	ErrorCode       string        `json:"error_code,omitempty"`
	ErrorMessage    string        `json:"error_message,omitempty"`
	LastSeenAt      time.Time     `json:"last_seen_at"`
	LastCheckedAt   time.Time     `json:"last_checked_at"`
	LatencyMs       int64         `json:"latency_ms"`
	MissedBeats     int           `json:"missed_beats"`
	ConnectionType  string        `json:"connection_type"` // "wifi" | "hotspot" | "manual"
	Host            string        `json:"host"`
	Port            int           `json:"port"`
	IsTLS           bool          `json:"is_tls"`
	Fingerprint     string        `json:"fingerprint,omitempty"`
	Capabilities    []string      `json:"capabilities,omitempty"`
	AppVersion      string        `json:"app_version,omitempty"`
	ProtocolVersion string        `json:"protocol_version,omitempty"`
	IsPaired        bool          `json:"is_paired"`
}

// CanSend returns true if transfers to this peer are allowed.
func (p PeerPresence) CanSend() bool {
	return p.IsPaired && (p.State == StateOnline || p.State == StateBusy)
}
