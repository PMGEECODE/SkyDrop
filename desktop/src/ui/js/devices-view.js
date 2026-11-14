/**
 * Devices view: handles discovery, pairing, two-dimensional presence, and peer lists.
 */
class DevicesView {
  constructor(app) {
    this.app = app;
    this.banner = document.getElementById('connection-banner');
    this.bannerText = document.getElementById('banner-text');
    this.pairedContainer = document.getElementById('paired-devices-container');
    this.discoveredContainer = document.getElementById('discovered-devices-container');
    this.announcer = document.getElementById('presence-announcer');
    this.modal = document.getElementById('modal-pair');
    this.qrImg = document.getElementById('pair-qr-img');
    this.pinText = document.getElementById('pair-pin-text');
    this.diagModal = document.getElementById('modal-diagnostics');
    this.diagContent = document.getElementById('diagnostics-content');
    this.pairModalContent = document.getElementById('pair-modal-content');
    this.pairModalSuccess = document.getElementById('pair-modal-success');
    this.pairSuccessDevice = document.getElementById('pair-success-device');
    this.pairPollTimer = null;
    this.currentPairingUri = '';
    this.lastPeerStates = new Map(); // peerId -> presenceState string
    this.lastPeersData = null;

    this.initEvents();
    this.initPresenceListener();
  }

  initEvents() {
    document.getElementById('btn-pair-new').addEventListener('click', () => this.openPairModal());
    document.getElementById('btn-close-modal').addEventListener('click', () => this.closePairModal());
    document.getElementById('btn-copy-pairing-uri').addEventListener('click', () => {
      if (this.currentPairingUri) {
        navigator.clipboard.writeText(this.currentPairingUri);
        const btn = document.getElementById('btn-copy-pairing-uri');
        const originalText = btn.textContent;
        btn.innerHTML = '<span class="material-symbols-rounded" style="font-size: 14px; vertical-align: middle; margin-right: 4px;">check_circle</span> Copied!';
        setTimeout(() => (btn.innerHTML = originalText), 2000);
      }
    });

    this.modal.addEventListener('click', (e) => {
      if (e.target === this.modal) this.closePairModal();
    });

    // Diagnose Modal
    const diagBtn = document.getElementById('btn-diagnose');
    const closeDiagBtn = document.getElementById('btn-close-diagnostics-modal');
    const copyDiagBtn = document.getElementById('btn-copy-diagnostics');
    this.rawDiagnosticsReport = '';

    if (diagBtn && this.diagModal) {
      diagBtn.addEventListener('click', async () => {
        this.diagModal.classList.add('active');
        if (this.diagContent) {
          this.diagContent.innerHTML = '<div style="color: var(--text-muted); padding: 20px; text-align: center;">Collecting system diagnostics...</div>';
        }
        try {
          const res = await window.skydrop.getDiagnostics();
          if (res.ok && res.data) {
            this.renderColorizedDiagnostics(res.data);
          } else {
            const report = res.error || 'No diagnostics available';
            this.rawDiagnosticsReport = report;
            if (this.diagContent) {
              this.diagContent.innerHTML = `<div class="diag-line diag-error">${escapeHtml(report)}</div>`;
            }
          }
        } catch (err) {
          const errStr = 'Error: ' + err.message;
          this.rawDiagnosticsReport = errStr;
          if (this.diagContent) {
            this.diagContent.innerHTML = `<div class="diag-line diag-error">${escapeHtml(errStr)}</div>`;
          }
        }
      });

      if (closeDiagBtn) {
        closeDiagBtn.addEventListener('click', () => this.diagModal.classList.remove('active'));
      }
      this.diagModal.addEventListener('click', (e) => {
        if (e.target === this.diagModal) this.diagModal.classList.remove('active');
      });
      if (copyDiagBtn) {
        copyDiagBtn.addEventListener('click', () => {
          const report = this.rawDiagnosticsReport || (this.diagContent && this.diagContent.textContent) || '';
          if (report) {
            navigator.clipboard.writeText(report);
            const orig = copyDiagBtn.textContent;
            copyDiagBtn.innerHTML = '<span class="material-symbols-rounded" style="font-size: 14px; vertical-align: middle; margin-right: 4px;">check_circle</span> Copied!';
            setTimeout(() => (copyDiagBtn.innerHTML = orig), 2000);
          }
        });
      }
    }

    const closeManualX = document.getElementById('btn-close-manual-x');
    if (closeManualX) {
      closeManualX.addEventListener('click', () => {
        const m = document.getElementById('modal-manual-peer');
        if (m) m.classList.remove('active');
      });
    }

    const manualBtn = document.getElementById('btn-manual-peer');
    const manualModal = document.getElementById('modal-manual-peer');
    const closeManualBtn = document.getElementById('btn-close-manual-modal');
    const saveManualBtn = document.getElementById('btn-save-manual-peer');
    const manualIpInput = document.getElementById('manual-peer-ip');
    const manualPortInput = document.getElementById('manual-peer-port');
    const manualNameInput = document.getElementById('manual-peer-name');

    if (manualBtn && manualModal) {
      manualBtn.addEventListener('click', () => {
        manualModal.classList.add('active');
        manualIpInput.focus();
      });
      if (closeManualBtn) {
        closeManualBtn.addEventListener('click', () => manualModal.classList.remove('active'));
      }
      manualModal.addEventListener('click', (e) => {
        if (e.target === manualModal) manualModal.classList.remove('active');
      });
      if (saveManualBtn) {
        saveManualBtn.addEventListener('click', async () => {
          const host = manualIpInput.value.trim();
          const port = parseInt(manualPortInput.value.trim(), 10) || 8444;
          const name = manualNameInput.value.trim();
          if (!host) {
            alert('Please enter a valid IP address');
            return;
          }
          saveManualBtn.textContent = 'Adding...';
          try {
            await window.skydrop.addManualPeer({ host, port, name });
            manualModal.classList.remove('active');
            manualIpInput.value = '';
            manualNameInput.value = '';
            this.app.pollPeers();
          } catch (err) {
            alert('Failed to add manual peer: ' + err.message);
          } finally {
            saveManualBtn.textContent = 'Add Device';
          }
        });
      }
    }
  }

  initPresenceListener() {
    if (window.skydrop && typeof window.skydrop.onPresenceUpdate === 'function') {
      window.skydrop.onPresenceUpdate((eventData) => {
        if (!eventData) return;
        if (eventData.type === 'pair_success') {
          this.handlePairSuccess(eventData.device_name || 'Device');
          return;
        }
        if (!eventData.peer_id || !this.lastPeersData) return;
        const peers = this.lastPeersData.peers || [];
        const match = peers.find((p) => p.id === eventData.peer_id);
        if (match) {
          match.presence_state = eventData.state;
          match.is_connected = eventData.state === 'ONLINE' || eventData.state === 'BUSY';
          match.latency_ms = eventData.latency_ms;
          match.error_code = eventData.error_code;
          match.error_message = eventData.error_message;
          this.render(this.lastPeersData);
        }
      });
    }
  }

  async openPairModal() {
    this.modal.classList.add('active');
    this.resetPairModalState();
    this.pinText.textContent = 'Loading...';
    try {
      const res = await window.skydrop.getPairSession();
      if (res.ok && res.data) {
        this.pinText.textContent = res.data.secret || '------';
        this.currentPairingUri = res.data.pairing_uri || '';
        if (res.data.qr_code) {
          this.qrImg.src = res.data.qr_code;
        }
      } else {
        this.pinText.textContent = 'Error';
      }
    } catch (err) {
      this.pinText.textContent = 'Error';
    }

    this.startPairStatusPolling();
  }

  resetPairModalState() {
    if (this.pairModalContent) this.pairModalContent.style.display = 'flex';
    if (this.pairModalSuccess) this.pairModalSuccess.style.display = 'none';
  }

  startPairStatusPolling() {
    this.stopPairStatusPolling();
    this.pairPollTimer = setInterval(async () => {
      if (!this.modal.classList.contains('active')) {
        this.stopPairStatusPolling();
        return;
      }
      try {
        if (window.skydrop && typeof window.skydrop.getPairStatus === 'function') {
          const statusRes = await window.skydrop.getPairStatus();
          if (statusRes && statusRes.ok && statusRes.data) {
            if (statusRes.data.last_paired && statusRes.data.last_paired.device_name) {
              const pairedName = statusRes.data.last_paired.device_name;
              this.handlePairSuccess(pairedName);
              return;
            }
          }
        }
      } catch (_) {}
    }, 1000);
  }

  stopPairStatusPolling() {
    if (this.pairPollTimer) {
      clearInterval(this.pairPollTimer);
      this.pairPollTimer = null;
    }
  }

  handlePairSuccess(deviceName) {
    this.stopPairStatusPolling();
    if (!this.modal.classList.contains('active')) {
      if (this.app) this.app.pollPeers();
      return;
    }

    if (this.pairModalContent) this.pairModalContent.style.display = 'none';
    if (this.pairModalSuccess) {
      this.pairModalSuccess.style.display = 'flex';
      if (this.pairSuccessDevice) {
        this.pairSuccessDevice.textContent = deviceName;
      }
    }

    // Auto-close modal after 1.5s and refresh peers
    setTimeout(() => {
      this.closePairModal();
      if (this.app) this.app.pollPeers();
    }, 1500);
  }

  closePairModal() {
    this.stopPairStatusPolling();
    this.modal.classList.remove('active');
    setTimeout(() => this.resetPairModalState(), 300);
  }

  normalizeName(name) {
    if (!name) return '';
    return name.replace(/\s*[\(_#]\d+[\)]?$/i, '').trim().toLowerCase();
  }

  arePeersDataEqual(a, b) {
    if (!a || !b) return a === b;
    const aPeers = a.peers || [];
    const bPeers = b.peers || [];
    const aPaired = a.paired || [];
    const bPaired = b.paired || [];
    if (aPeers.length !== bPeers.length || aPaired.length !== bPaired.length) return false;
    for (let i = 0; i < aPeers.length; i++) {
      const pA = aPeers[i];
      const pB = bPeers[i];
      if (
        pA.id !== pB.id ||
        pA.name !== pB.name ||
        pA.host !== pB.host ||
        pA.port !== pB.port ||
        pA.presence_state !== pB.presence_state ||
        pA.is_connected !== pB.is_connected ||
        pA.latency_ms !== pB.latency_ms ||
        pA.error_code !== pB.error_code ||
        pA.error_message !== pB.error_message
      ) {
        return false;
      }
    }
    for (let i = 0; i < aPaired.length; i++) {
      const pA = aPaired[i];
      const pB = bPaired[i];
      if (
        pA.id !== pB.id ||
        pA.device_name !== pB.device_name ||
        pA.host !== pB.host ||
        pA.port !== pB.port ||
        pA.auth_token !== pB.auth_token
      ) {
        return false;
      }
    }
    return true;
  }

  render(peersData) {
    if (this.lastPeersData && this.arePeersDataEqual(peersData, this.lastPeersData)) {
      return;
    }
    this.lastPeersData = peersData;
    const peers = (peersData && peersData.peers) || [];
    const paired = (peersData && peersData.paired) || [];

    // Screen reader accessibility announcements for state changes
    if (this.announcer) {
      for (const p of peers) {
        const last = this.lastPeerStates.get(p.id);
        const curr = p.presence_state || (p.is_connected ? 'ONLINE' : 'OFFLINE');
        if (last && last !== curr) {
          this.announcer.textContent = `${p.name} is now ${curr.toLowerCase()}`;
        }
        this.lastPeerStates.set(p.id, curr);
      }
    }

    // Top Connection Banner
    const activeConnected = peers.find((p) => p.is_paired && p.is_connected);
    if (activeConnected) {
      this.banner.className = 'status-banner connected';
      const latText = activeConnected.latency_ms ? ` (${activeConnected.latency_ms}ms)` : '';
      this.banner.innerHTML = `<span role="status">🟢</span> <span>Connected to ${escapeHtml(activeConnected.name)}${latText} • Ready for drops</span>`;
    } else if (peers.length > 0) {
      this.banner.className = 'status-banner connected';
      this.banner.innerHTML = `<span role="status">🟢</span> <span>${peers.length} peer(s) found on local network</span>`;
    } else {
      this.banner.className = 'status-banner offline';
      this.banner.innerHTML = `<span role="status">⚪</span> <span>Searching local network for peers...</span>`;
    }

    // Render Paired Devices
    if (paired.length === 0) {
      this.pairedContainer.innerHTML = '';
    } else {
      let html = `<div style="font-size: 12px; font-weight: 700; color: var(--text-muted); margin-bottom: 6px; text-transform: uppercase;">Paired Devices</div>`;
      for (const p of paired) {
        const livePeer = peers.find((item) => item.id === p.id || item.auth_token === p.auth_token || item.name.toLowerCase() === p.device_name.toLowerCase());
        const isOnline = livePeer && livePeer.is_connected;
        const state = (livePeer && livePeer.presence_state) || (isOnline ? 'ONLINE' : 'OFFLINE');
        const stateBadge = this.formatPresenceBadge(state);
        const metaText = this.formatDeviceMeta(livePeer, state);

        html += `
          <div class="device-item" data-peer-id="${p.id}">
            <div class="device-left">
              <div class="device-avatar">${p.device_name.toLowerCase().includes('android') || p.device_name.toLowerCase().includes('sm-') || p.device_name.toLowerCase().includes('phone') ? '📱' : '💻'}</div>
              <div class="device-info">
                <div class="device-name">
                  <span>${escapeHtml(p.device_name)}</span>
                  <span class="badge-pill badge-paired">Paired</span>
                  ${stateBadge}
                </div>
                <div class="device-meta">${metaText}</div>
              </div>
            </div>
            <div class="device-actions">
              ${isOnline
                ? `<button class="btn-tiny" onclick="window.devicesView.selectAndSend('${p.id}')">Send</button>`
                : `<button class="btn-tiny" style="background: var(--bg-surface-elevated);" onclick="window.devicesView.retry('${p.id}', this)">Retry now</button>`
              }
              <button class="btn-icon-danger" title="Unpair Device" onclick="window.devicesView.unpair('${p.id}')">✕</button>
            </div>
          </div>
        `;
      }
      this.pairedContainer.innerHTML = html;
    }

    // Render Discovered Devices (not paired)
    // Filter out any peer already paired (by host, ID, or normalized name)
    const pairedNames = new Set(paired.map((p) => this.normalizeName(p.device_name)));
    const pairedHosts = new Set(paired.map((p) => p.host).filter(Boolean));
    const pairedIds = new Set(paired.map((p) => p.id));

    const unpairedDiscovered = peers.filter((p) => {
      if (p.is_paired) return false;
      if (pairedIds.has(p.id)) return false;
      if (p.host && pairedHosts.has(p.host)) return false;
      const norm = this.normalizeName(p.name);
      if (pairedNames.has(norm)) return false;
      return true;
    });
    if (unpairedDiscovered.length === 0 && paired.length === 0) {
      this.discoveredContainer.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">📡</div>
          <div style="font-size: 13px; font-weight: 600;">No devices found yet</div>
          <div style="font-size: 11px; margin-top: 4px;">Make sure your phone is on the same Wi-Fi network and has SkyDrop open.</div>
        </div>
      `;
    } else if (unpairedDiscovered.length > 0) {
      let html = `<div style="font-size: 12px; font-weight: 700; color: var(--text-muted); margin: 12px 0 6px; text-transform: uppercase;">Discovered Nearby</div>`;
      for (const p of unpairedDiscovered) {
        html += `
          <div class="device-item">
            <div class="device-left">
              <div class="device-avatar">📱</div>
              <div class="device-info">
                <div class="device-name">${escapeHtml(p.name)}</div>
                <div class="device-meta">${p.host}:${p.port}</div>
              </div>
            </div>
            <div class="device-actions">
              <button class="btn-tiny" onclick="window.devicesView.openPairModal()">Pair</button>
            </div>
          </div>
        `;
      }
      this.discoveredContainer.innerHTML = html;
    } else {
      this.discoveredContainer.innerHTML = '';
    }
  }

  formatPresenceBadge(state) {
    const s = (state || 'UNKNOWN').toUpperCase();
    const config = {
      ONLINE: { cls: 'badge-online', icon: '🟢', label: 'Online' },
      BUSY: { cls: 'badge-busy', icon: '🔵', label: 'Busy' },
      DEGRADED: { cls: 'badge-degraded', icon: '🟡', label: 'Degraded' },
      OFFLINE: { cls: 'badge-offline', icon: '⚪', label: 'Offline' },
      CHECKING: { cls: 'badge-checking', icon: '🟣', label: 'Checking' },
      ERROR: { cls: 'badge-error', icon: '🔴', label: 'Error' },
      UNKNOWN: { cls: 'badge-offline', icon: '⚪', label: 'Unknown' }
    }[s] || { cls: 'badge-offline', icon: '⚪', label: s };

    return `<span class="badge-status ${config.cls}" role="status" aria-label="${config.label}">${config.icon} ${config.label}</span>`;
  }

  formatDeviceMeta(peer, state) {
    if (!peer) return 'Offline';
    const s = (state || 'UNKNOWN').toUpperCase();
    switch (s) {
      case 'ONLINE':
        return peer.latency_ms ? `Online • ${peer.latency_ms}ms • Ready` : 'Online • Ready to drop';
      case 'BUSY':
        return 'Busy • Active file transfer in progress';
      case 'DEGRADED':
        return 'Degraded • Unstable connection or missed beats';
      case 'CHECKING':
        return 'Probing device connection...';
      case 'ERROR':
        return peer.error_message ? `Error: ${escapeHtml(peer.error_message)}` : (peer.error_code || 'Connection error');
      case 'OFFLINE':
      default:
        return peer.last_seen_at ? `Offline • Last seen ${this.formatTimeAgo(peer.last_seen_at)}` : 'Offline • Not reachable';
    }
  }

  formatTimeAgo(dateStr) {
    try {
      const diff = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000);
      if (diff < 60) return `${diff}s ago`;
      if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
      return `${Math.floor(diff / 3600)}h ago`;
    } catch (_) {
      return '';
    }
  }

  async retry(peerId, btnEl) {
    if (btnEl) {
      btnEl.disabled = true;
      btnEl.textContent = 'Checking...';
    }
    try {
      await window.skydrop.retryPresence(peerId);
      setTimeout(() => {
        if (this.app) this.app.pollPeers();
      }, 500);
    } catch (err) {
      console.warn('Retry error:', err);
    } finally {
      if (btnEl) {
        btnEl.disabled = false;
        btnEl.textContent = 'Retry now';
      }
    }
  }

  async unpair(id) {
    if (confirm('Unpair this device?')) {
      await window.skydrop.unpairPeer(id);
      this.app.refreshPeers();
    }
  }

  selectAndSend(peerId) {
    this.app.switchTab('screen-send');
    const select = document.getElementById('target-peer-select');
    if (select) {
      select.value = peerId;
      select.dispatchEvent(new Event('change'));
    }
  }

  renderColorizedDiagnostics(reportData) {
    if (!this.diagContent) return;
    const jsonStr = typeof reportData === 'string' ? reportData : JSON.stringify(reportData, null, 2);
    this.rawDiagnosticsReport = jsonStr;

    const dot = document.getElementById('diagnostics-health-dot');
    const label = document.getElementById('diagnostics-health-label');
    const timestamp = document.getElementById('diagnostics-timestamp');
    if (timestamp) {
      timestamp.textContent = new Date().toLocaleTimeString();
    }

    let hasErrors = false;
    let hasWarnings = false;

    // Process line-by-line for high-contrast, structured color coding
    const lines = jsonStr.split('\n');
    const htmlLines = lines.map((line) => {
      const lower = line.toLowerCase();
      const isError = lower.includes('"error') || lower.includes('failed') || lower.includes('unreachable') || lower.includes('offline') || lower.includes('disconnected');
      const isWarning = lower.includes('warn') || lower.includes('warning') || lower.includes('degraded') || (lower.includes('missed_beats') && !lower.includes('missed_beats": 0'));
      const isSuccess = lower.includes('"online"') || lower.includes('"connected"') || (lower.includes('true') && (lower.includes('paired') || lower.includes('tls') || lower.includes('ok')));

      if (isError) hasErrors = true;
      else if (isWarning) hasWarnings = true;

      // Syntax highlight JSON tokens
      let formatted = escapeHtml(line);
      formatted = formatted
        .replace(/("[\w_\-]+")\s*:/g, '<span class="diag-key">$1</span>:')
        .replace(/:\s*(".*?")/g, ': <span class="diag-str">$1</span>')
        .replace(/:\s*(\b\d+(\.\d+)?\b)/g, ': <span class="diag-num">$1</span>')
        .replace(/:\s*(true|false|null)/g, ': <span class="diag-bool">$1</span>');

      if (isError) {
        return `<div class="diag-line diag-error">${formatted}</div>`;
      } else if (isWarning) {
        return `<div class="diag-line diag-warning">${formatted}</div>`;
      } else if (isSuccess) {
        return `<div class="diag-line diag-success">${formatted}</div>`;
      }
      return `<div class="diag-line">${formatted}</div>`;
    });

    this.diagContent.innerHTML = htmlLines.join('');

    if (dot && label) {
      if (hasErrors) {
        dot.className = 'health-dot error';
        label.textContent = 'Errors Detected';
        label.style.color = 'var(--color-danger)';
      } else if (hasWarnings) {
        dot.className = 'health-dot warning';
        label.textContent = 'Warnings Detected';
        label.style.color = 'var(--color-accent)';
      } else {
        dot.className = 'health-dot';
        label.textContent = 'System Operational';
        label.style.color = 'var(--color-success)';
      }
    }
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/[&<>'"]/g, (tag) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    "'": '&#39;',
    '"': '&quot;'
  }[tag] || tag));
}

window.DevicesView = DevicesView;
