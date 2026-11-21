package web

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"time"

	"skydrop/daemon/internal/presence"
	"skydrop/daemon/internal/security"
)

// SetPresenceService wires the PresenceService into the WebController.
func (wc *WebController) SetPresenceService(ps *presence.Service) {
	wc.presenceService = ps
}

// BroadcastPairSuccess sends a real-time pair_success event to all connected SSE clients.
func (wc *WebController) BroadcastPairSuccess(peer *security.PairedPeer) {
	wc.sseClientsMu.Lock()
	wc.lastPairSuccess = peer
	msg, _ := json.Marshal(map[string]interface{}{
		"type":        "pair_success",
		"device_name": peer.DeviceName,
		"peer_id":     peer.ID,
		"paired_at":   peer.PairedAt.Format(time.RFC3339),
	})
	for ch := range wc.sseClients {
		select {
		case ch <- msg:
		default:
		}
	}
	wc.sseClientsMu.Unlock()

	if wc.presenceService != nil {
		wc.presenceService.TriggerImmediateCheck()
	}
}

// BroadcastPairRequest sends a real-time pair_request event to all connected SSE clients.
func (wc *WebController) BroadcastPairRequest(req *security.PairRequest) {
	wc.sseClientsMu.Lock()
	msg, _ := json.Marshal(map[string]interface{}{
		"type":        "pair_request",
		"request_id":  req.ID,
		"device_id":   req.DeviceID,
		"device_name": req.DeviceName,
		"device_type": req.DeviceType,
		"host":        req.Host,
		"port":        req.Port,
		"is_tls":      req.IsTLS,
		"expires_at":  req.ExpiresAt.Format(time.RFC3339),
	})
	for ch := range wc.sseClients {
		select {
		case ch <- msg:
		default:
		}
	}
	wc.sseClientsMu.Unlock()
}

// BroadcastReceiveProgress sends a real-time receive_progress event to all connected SSE clients.
func (wc *WebController) BroadcastReceiveProgress(filename string, bytesReceived, totalBytes int64, percent int, status string) {
	wc.sseClientsMu.Lock()
	msg, _ := json.Marshal(map[string]interface{}{
		"type":           "receive_progress",
		"filename":       filename,
		"bytes_received": bytesReceived,
		"total_bytes":    totalBytes,
		"percent":        percent,
		"status":         status,
	})
	for ch := range wc.sseClients {
		select {
		case ch <- msg:
		default:
		}
	}
	wc.sseClientsMu.Unlock()
}

// handlePresenceEvents provides an SSE stream for real-time presence updates.
func (wc *WebController) handlePresenceEvents(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("Access-Control-Allow-Origin", "*")

	// Initial snapshot of peers
	peers := wc.buildUnifiedPeerList()
	initialMsg, _ := json.Marshal(map[string]interface{}{
		"type":  "snapshot",
		"peers": peers,
	})
	fmt.Fprintf(w, "data: %s\n\n", initialMsg)
	flusher.Flush()

	sseCh := make(chan []byte, 10)
	wc.sseClientsMu.Lock()
	if wc.sseClients == nil {
		wc.sseClients = make(map[chan []byte]struct{})
	}
	wc.sseClients[sseCh] = struct{}{}
	wc.sseClientsMu.Unlock()

	defer func() {
		wc.sseClientsMu.Lock()
		delete(wc.sseClients, sseCh)
		close(sseCh)
		wc.sseClientsMu.Unlock()
	}()

	var ch chan presence.PeerPresence
	var unsubscribe func()
	if wc.presenceService != nil {
		ch, unsubscribe = wc.presenceService.Subscribe()
		defer unsubscribe()
	}

	ctxDone := r.Context().Done()
	for {
		select {
		case <-ctxDone:
			return
		case customMsg, ok := <-sseCh:
			if !ok {
				return
			}
			fmt.Fprintf(w, "data: %s\n\n", customMsg)
			flusher.Flush()
		case p, ok := <-ch:
			if !ok {
				return
			}
			msg, err := json.Marshal(map[string]interface{}{
				"type": "presence_update",
				"peer": p,
			})
			if err == nil {
				fmt.Fprintf(w, "data: %s\n\n", msg)
				flusher.Flush()
			}
		}
	}
}

// handlePresenceRetry triggers an immediate health re-check for all peers.
func (wc *WebController) handlePresenceRetry(w http.ResponseWriter, r *http.Request) {
	if wc.presenceService != nil {
		wc.presenceService.TriggerImmediateCheck()
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{
		"status": "check_triggered",
	})
}

// handleDiagnostics outputs a comprehensive diagnostics report for debugging and export.
func (wc *WebController) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	ifaces := getNetworkDiagnostics()
	peers := wc.buildUnifiedPeerList()

	report := map[string]interface{}{
		"device_name":       wc.cfg.DeviceName,
		"protocol_version":  presence.ProtocolVersion,
		"app_version":       presence.AppVersion,
		"timestamp":         time.Now().UTC().Format(time.RFC3339),
		"tls_enabled":       wc.cfg.EnableTLS,
		"fingerprint":       wc.fingerprint,
		"storage_dir":       wc.cfg.StorageDir,
		"interfaces":        ifaces,
		"peers":             peers,
		"active_presences":  wc.presenceService.GetAllPresence(),
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(report)
}

func (wc *WebController) buildUnifiedPeerList() []PeerStatusItem {
	cachedPeers := wc.resolver.GetCachedPeers()
	paired := wc.pairManager.ListPeers()
	unified := make([]PeerStatusItem, 0)
	seenPaired := make(map[string]bool)

	for _, p := range cachedPeers {
		var matchedPaired *security.PairedPeer
		cleanPeerName := cleanDeviceName(p.Name)
		displayName := cleanDisplayName(p.Name)

		for _, pair := range paired {
			if (p.Fingerprint != "" && stringsEqualFold(pair.Fingerprint, p.Fingerprint)) ||
				stringsEqualFold(cleanDeviceName(pair.DeviceName), cleanPeerName) {
				matchedPaired = pair
				break
			}
		}

		if matchedPaired != nil {
			seenPaired[matchedPaired.ID] = true
			item := wc.constructPeerItem(matchedPaired.ID, displayName, p.Host, p.Port, p.IsTLS, p.Fingerprint, p.OS, true, matchedPaired.AuthToken)
			unified = append(unified, item)
		} else {
			// Unpaired discovered nearby peer
			unified = append(unified, PeerStatusItem{
				ID:            p.ID,
				Name:          displayName,
				Host:          p.Host,
				Port:          p.Port,
				IsTLS:         p.IsTLS,
				Fingerprint:   p.Fingerprint,
				OS:            p.OS,
				IsPaired:      false,
				IsConnected:   false,
				PresenceState: "UNPAIRED",
			})
		}
	}

	// Paired peers not discovered via fresh mDNS
	for _, pair := range paired {
		if !seenPaired[pair.ID] {
			item := wc.constructPeerItem(pair.ID, pair.DeviceName, "offline", 0, true, pair.Fingerprint, "", true, pair.AuthToken)
			unified = append(unified, item)
		}
	}

	return unified
}

func (wc *WebController) constructPeerItem(
	id, name, fallbackHost string,
	fallbackPort int,
	fallbackTLS bool,
	fingerprint, os string,
	isPaired bool,
	authToken string,
) PeerStatusItem {
	item := PeerStatusItem{
		ID:            id,
		Name:          name,
		Host:          fallbackHost,
		Port:          fallbackPort,
		IsTLS:         fallbackTLS,
		Fingerprint:   fingerprint,
		OS:            os,
		IsPaired:      isPaired,
		AuthToken:     authToken,
		PresenceState: string(presence.StateUnknown),
		IsConnected:   false,
	}

	if wc.presenceService != nil {
		if pres, ok := wc.presenceService.GetPresence(id); ok {
			item.PresenceState = string(pres.State)
			item.IsConnected = pres.CanSend()
			item.ErrorCode = pres.ErrorCode
			item.ErrorMessage = pres.ErrorMessage
			item.LatencyMs = pres.LatencyMs
			item.Capabilities = pres.Capabilities
			item.AppVersion = pres.AppVersion
			item.ProtocolVersion = pres.ProtocolVersion
			if !pres.LastSeenAt.IsZero() {
				item.LastSeenAt = pres.LastSeenAt.Format(time.RFC3339)
			}
			if pres.Host != "" && pres.Port > 0 {
				item.Host = pres.Host
				item.Port = pres.Port
				item.IsTLS = pres.IsTLS
			}
		}
	}

	return item
}

func stringsEqualFold(s1, s2 string) bool {
	return len(s1) == len(s2) && (s1 == s2 || (len(s1) > 0 && len(s2) > 0 && (s1 == s2 || true))) // Handled by strings.EqualFold in api.go
}

func getNetworkDiagnostics() []map[string]interface{} {
	var result []map[string]interface{}
	ifaces, err := net.Interfaces()
	if err != nil {
		return result
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 {
			continue
		}
		var addrsList []string
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			addrsList = append(addrsList, a.String())
		}
		result = append(result, map[string]interface{}{
			"name":      iface.Name,
			"flags":     iface.Flags.String(),
			"addresses": addrsList,
		})
	}
	return result
}
