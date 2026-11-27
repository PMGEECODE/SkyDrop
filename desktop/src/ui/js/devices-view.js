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

    // Incoming pairing request modal elements
    this.incomingModal = document.getElementById('modal-pair-incoming');
    this.incomingTitle = document.getElementById('pair-incoming-title');
    this.incomingMessage = document.getElementById('pair-incoming-message');
    this.incomingMeta = document.getElementById('pair-incoming-meta');
    this.incomingIcon = document.getElementById('pair-incoming-icon');
    this.btnIncomingAccept = document.getElementById('btn-pair-incoming-accept');
    this.btnIncomingDecline = document.getElementById('btn-pair-incoming-decline');

    // Outbound PIN input modal elements
    this.pinInputModal = document.getElementById('modal-pair-input');
    this.pinInputTitle = document.getElementById('pair-input-title');
    this.pinInputField = document.getElementById('pair-pin-input-field');
    this.pinInputError = document.getElementById('pair-pin-input-error');
    this.btnPinCancel = document.getElementById('btn-pair-input-cancel');
    this.btnPinSubmit = document.getElementById('btn-pair-input-submit');

    this.incomingQueue = [];
    this.activeIncomingReq = null;
    this.activeOutboundTarget = null;
    this.pairPollTimer = null;
    this.currentPairingUri = '';
    this.lastPeerStates = new Map(); // peerId -> presenceState string
    this.lastPeersData = null;

    this.initEvents();
    this.initPresenceListener();
    this.initHotspot();

    this.checkIncomingPairRequests();
    this.incomingPollTimer = setInterval(() => this.checkIncomingPairRequests(), 3000);
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

    // Incoming Pairing Approval Modal Events
    if (this.btnIncomingAccept) {
      this.btnIncomingAccept.addEventListener('click', async () => {
        if (!this.activeIncomingReq) return;
        const req = this.activeIncomingReq;
        const reqId = req.request_id || req.id;
        this.btnIncomingAccept.disabled = true;
        this.btnIncomingAccept.textContent = 'Accepting...';
        try {
          const res = await window.skydrop.pairAcceptIncoming(reqId);
          this.closeIncomingModal();
          if (res && res.ok && res.data) {
            this.openPairModal(res.data);
          } else {
            this.openPairModal();
          }
        } catch (err) {
          alert('Failed to accept pairing request: ' + err.message);
        } finally {
          this.btnIncomingAccept.disabled = false;
          this.btnIncomingAccept.textContent = 'Accept';
        }
      });
    }

    if (this.btnIncomingDecline) {
      this.btnIncomingDecline.addEventListener('click', async () => {
        if (!this.activeIncomingReq) return;
        const req = this.activeIncomingReq;
        const reqId = req.request_id || req.id;
        try {
          await window.skydrop.pairDeclineIncoming(reqId);
        } catch (_) {}
        this.closeIncomingModal();
      });
    }

    if (this.incomingModal) {
      this.incomingModal.addEventListener('click', (e) => {
        if (e.target === this.incomingModal) {
          if (this.activeIncomingReq) {
            const reqId = this.activeIncomingReq.request_id || this.activeIncomingReq.id;
            window.skydrop.pairDeclineIncoming(reqId).catch(() => {});
          }
          this.closeIncomingModal();
        }
      });
    }

    // Outbound PIN Input Modal Events
    if (this.btnPinSubmit) {
      this.btnPinSubmit.addEventListener('click', () => this.submitPairPin());
    }
    if (this.pinInputField) {
      this.pinInputField.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') this.submitPairPin();
      });
      this.pinInputField.addEventListener('input', (e) => {
        const cleaned = e.target.value.replace(/\D/g, '').slice(0, 6);
        e.target.value = cleaned;
        if (cleaned.length === 6) {
          this.submitPairPin();
        }
      });
    }
    if (this.btnPinCancel) {
      this.btnPinCancel.addEventListener('click', () => this.closePinInputModal());
    }
    if (this.pinInputModal) {
      this.pinInputModal.addEventListener('click', (e) => {
        if (e.target === this.pinInputModal) {
          this.closePinInputModal();
        } else if (e.target && !e.target.closest('button')) {
          this.focusPinInput();
        }
      });
    }

    // Auto-focus PIN field when typing numbers or pressing keys while PIN modal is active
    window.addEventListener('keydown', (e) => {
      if (!this.pinInputModal || !this.pinInputModal.classList.contains('active')) return;
      if (e.key === 'Escape') {
        this.closePinInputModal();
        return;
      }
      if (this.pinInputField && document.activeElement !== this.pinInputField) {
        if (/^[0-9]$/.test(e.key) || e.key === 'Backspace') {
          this.pinInputField.focus();
        }
      }
    });

    // Re-focus PIN field when desktop window regains OS focus while modal is active
    window.addEventListener('focus', () => {
      if (this.pinInputModal && this.pinInputModal.classList.contains('active')) {
        this.focusPinInput();
      }
    });
  }

  initPresenceListener() {
    if (window.skydrop && typeof window.skydrop.onPresenceUpdate === 'function') {
      window.skydrop.onPresenceUpdate((eventData) => {
        if (!eventData) return;
        if (eventData.type === 'pair_request') {
          const req = eventData.request || eventData;
          if (req && (req.request_id || req.id)) {
            this.enqueueIncomingRequest(req);
          }
          return;
        }
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

  enqueueIncomingRequest(req) {
    if (!req) return;
    const reqId = req.request_id || req.id;
    if (!reqId) return;
    if (this.activeIncomingReq && (this.activeIncomingReq.id === reqId || this.activeIncomingReq.request_id === reqId)) return;
    if (this.incomingQueue.some((r) => (r.id === reqId || r.request_id === reqId))) return;
    req.id = reqId;
    req.request_id = reqId;
    this.incomingQueue.push(req);
    if (!this.activeIncomingReq) {
      this.showNextIncomingRequest();
    }
  }

  showNextIncomingRequest() {
    if (this.incomingQueue.length === 0) {
      this.activeIncomingReq = null;
      if (this.incomingModal) this.incomingModal.classList.remove('active');
      return;
    }
    this.activeIncomingReq = this.incomingQueue.shift();
    const req = this.activeIncomingReq;
    if (this.incomingMessage) {
      this.incomingMessage.textContent = `${req.device_name || 'Nearby Device'} wants to pair with this device.`;
    }
    if (this.incomingMeta) {
      const hostStr = req.host ? `${req.host}:${req.port || 8444}` : '';
      const typeStr = req.device_type ? `[${req.device_type}]` : '';
      this.incomingMeta.textContent = [typeStr, hostStr].filter(Boolean).join(' • ');
    }
    if (this.incomingIcon) {
      const dt = (req.device_type || '').toLowerCase();
      const dn = (req.device_name || '').toLowerCase();
      if (dt.includes('tv') || dn.includes('tv')) {
        this.incomingIcon.textContent = '📺';
      } else if (dt.includes('android') || dt.includes('phone') || dn.includes('phone')) {
        this.incomingIcon.textContent = '📱';
      } else {
        this.incomingIcon.textContent = '💻';
      }
    }
    if (this.incomingModal) {
      this.incomingModal.classList.add('active');
    }
  }

  closeIncomingModal() {
    if (this.incomingModal) {
      this.incomingModal.classList.remove('active');
    }
    this.activeIncomingReq = null;
    setTimeout(() => this.showNextIncomingRequest(), 300);
  }

  async checkIncomingPairRequests() {
    if (!window.skydrop || typeof window.skydrop.pairGetIncoming !== 'function') return;
    try {
      const res = await window.skydrop.pairGetIncoming();
      if (res && res.ok && Array.isArray(res.data)) {
        for (const req of res.data) {
          this.enqueueIncomingRequest(req);
        }
      }
    } catch (_) {}
  }

  async startPairingWithDevice(peerId) {
    const peers = (this.lastPeersData && this.lastPeersData.peers) || [];
    const p = peers.find((item) => item.id === peerId);
    if (!p) {
      this.openPairModal();
      return;
    }

    const targetHost = p.host;
    const targetPort = p.port || (p.is_tls ? 8443 : 8444);
    const isTls = Boolean(p.is_tls);
    const deviceName = p.name || 'Remote Device';

    const btn = document.querySelector(`[data-peer-pair-btn="${p.id}"]`);
    if (btn) {
      btn.disabled = true;
      btn.textContent = 'Requesting...';
    }

    try {
      const res = await window.skydrop.pairInitiate({
        host: targetHost,
        port: targetPort,
        is_tls: isTls,
        device_name: deviceName
      });

      if (res && res.ok && res.data) {
        if (res.data.status === 'accepted') {
          this.openPinInputModal({
            host: targetHost,
            port: targetPort,
            is_tls: isTls,
            device_name: deviceName
          });
          return;
        } else if (res.data.status === 'declined') {
          alert(`Pairing request was declined by ${deviceName}.`);
          return;
        }
      }
      const errMsg = (res && res.error) || 'Pairing request timed out or was not accepted.';
      alert(errMsg);
    } catch (err) {
      alert('Failed to initiate pairing: ' + err.message);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.textContent = 'Pair';
      }
    }
  }

  openPinInputModal(target) {
    this.activeOutboundTarget = target;
    if (this.pinInputField) {
      this.pinInputField.value = '';
    }
    if (this.pinInputError) {
      this.pinInputError.style.display = 'none';
      this.pinInputError.textContent = '';
    }
    if (this.pinInputTitle && target && target.device_name) {
      this.pinInputTitle.textContent = `Pair with ${target.device_name}`;
    }
    if (this.pinInputModal) {
      this.pinInputModal.classList.add('active');
      this.focusPinInput();
    }
  }

  focusPinInput() {
    if (!this.pinInputField) return;
    try {
      if (typeof window !== 'undefined' && typeof window.focus === 'function') {
        window.focus();
      }
    } catch (_) {}
    this.pinInputField.focus();
    this.pinInputField.select();
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(() => {
        if (this.pinInputField) {
          this.pinInputField.focus();
          this.pinInputField.select();
        }
      });
    }
    setTimeout(() => {
      if (this.pinInputField && this.pinInputModal && this.pinInputModal.classList.contains('active')) {
        this.pinInputField.focus();
      }
    }, 50);
  }

  closePinInputModal() {
    if (this.pinInputModal) {
      this.pinInputModal.classList.remove('active');
    }
    this.activeOutboundTarget = null;
  }

  async submitPairPin() {
    if (!this.activeOutboundTarget) return;
    const pin = this.pinInputField ? this.pinInputField.value.trim() : '';
    if (!pin || pin.length !== 6) {
      if (this.pinInputError) {
        this.pinInputError.textContent = 'Please enter a 6-digit PIN';
        this.pinInputError.style.display = 'block';
      }
      return;
    }
    if (this.btnPinSubmit) {
      this.btnPinSubmit.disabled = true;
      this.btnPinSubmit.textContent = 'Verifying...';
    }
    if (this.pinInputError) {
      this.pinInputError.style.display = 'none';
    }

    try {
      const res = await window.skydrop.pairConfirmSecret({
        host: this.activeOutboundTarget.host,
        port: this.activeOutboundTarget.port,
        is_tls: this.activeOutboundTarget.is_tls,
        secret: pin,
        device_name: this.activeOutboundTarget.device_name
      });

      if (res && res.ok) {
        const pairedName = (res.data && res.data.device_name) || this.activeOutboundTarget.device_name;
        this.closePinInputModal();
        this.handlePairSuccess(pairedName);
        if (this.app) this.app.refreshPeers();
      } else {
        const errMsg = (res && res.error) || 'Invalid pairing PIN. Please try again.';
        if (this.pinInputError) {
          this.pinInputError.textContent = errMsg;
          this.pinInputError.style.display = 'block';
        }
      }
    } catch (err) {
      if (this.pinInputError) {
        this.pinInputError.textContent = err.message || 'Connection error';
        this.pinInputError.style.display = 'block';
      }
    } finally {
      if (this.btnPinSubmit) {
        this.btnPinSubmit.disabled = false;
        this.btnPinSubmit.textContent = 'Pair';
      }
    }
  }

  async openPairModal(sessionData = null) {
    this.modal.classList.add('active');
    this.resetPairModalState();
    if (sessionData && sessionData.secret) {
      this.pinText.textContent = sessionData.secret || '------';
      this.currentPairingUri = sessionData.pairing_uri || '';
      if (sessionData.qr_code) {
        this.qrImg.src = sessionData.qr_code;
      }
    } else {
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

    // Top Connection Banner (hidden to prevent inaccurate single-device status when multiple peers are connected)
    if (this.banner) {
      this.banner.style.display = 'none';
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
    const seenEndpoints = new Set();

    const unpairedDiscovered = peers.filter((p) => {
      if (p.is_paired) return false;
      if (pairedIds.has(p.id)) return false;
      if (p.host && pairedHosts.has(p.host)) return false;
      const norm = this.normalizeName(p.name);
      if (pairedNames.has(norm)) return false;
      // Deduplicate discovered peers sharing identical host and port
      const endpoint = p.host ? `${p.host}:${p.port || 0}` : p.id;
      if (seenEndpoints.has(endpoint)) return false;
      seenEndpoints.add(endpoint);
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
              <button class="btn-tiny" data-peer-pair-btn="${p.id}" onclick="window.devicesView.startPairingWithDevice('${p.id}')">Pair</button>
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

  // ─── Hotspot UI & Lifecycle ────────────────────────────────────────────────

  initHotspot() {
    this.hotspotCard = document.getElementById('hotspot-card');
    this.btnToggleHotspot = document.getElementById('btn-toggle-hotspot');
    this.hotspotBadge = document.getElementById('hotspot-badge');
    this.hotspotSubtext = document.getElementById('hotspot-subtext');
    this.hotspotIcon = document.getElementById('hotspot-status-icon');
    this.hotspotActivePanel = document.getElementById('hotspot-active-panel');
    this.hotspotQrImg = document.getElementById('hotspot-qr-img');
    this.hotspotSsid = document.getElementById('hotspot-ssid-text');
    this.hotspotPassword = document.getElementById('hotspot-password-text');
    this.hotspotIp = document.getElementById('hotspot-ip-text');
    this.hotspotClients = document.getElementById('hotspot-clients-text');
    this.hotspotErrorBox = document.getElementById('hotspot-error-box');
    this.hotspotErrorText = document.getElementById('hotspot-error-text');
    this.btnHotspotDisconnect = document.getElementById('btn-hotspot-disconnect-confirm');
    this.btnRevealPassword = document.getElementById('btn-reveal-hotspot-password');
    this.btnCopyPassword = document.getElementById('btn-copy-hotspot-password');
    this.btnCopySsid = document.getElementById('btn-copy-hotspot-ssid');

    this.hotspotPollTimer = null;
    this.rawHotspotPassword = '';
    this.passwordRevealed = false;

    if (!this.btnToggleHotspot) return;

    this.refreshHotspotStatus();

    this.btnToggleHotspot.addEventListener('click', () => this.handleHotspotToggle());

    if (this.btnHotspotDisconnect) {
      this.btnHotspotDisconnect.addEventListener('click', () => {
        this.startHotspot(true);
      });
    }

    if (this.btnRevealPassword) {
      this.btnRevealPassword.addEventListener('click', () => {
        this.passwordRevealed = !this.passwordRevealed;
        if (this.passwordRevealed) {
          this.hotspotPassword.textContent = this.rawHotspotPassword;
          this.btnRevealPassword.textContent = '🔒';
        } else {
          this.hotspotPassword.textContent = '••••••••••••';
          this.btnRevealPassword.textContent = '👁';
        }
      });
    }

    if (this.btnCopyPassword) {
      this.btnCopyPassword.addEventListener('click', () => {
        if (this.rawHotspotPassword) {
          navigator.clipboard.writeText(this.rawHotspotPassword);
          const orig = this.btnCopyPassword.textContent;
          this.btnCopyPassword.textContent = '✓';
          setTimeout(() => (this.btnCopyPassword.textContent = orig), 1500);
        }
      });
    }

    if (this.btnCopySsid) {
      this.btnCopySsid.addEventListener('click', () => {
        const ssid = this.hotspotSsid ? this.hotspotSsid.textContent : '';
        if (ssid) {
          navigator.clipboard.writeText(ssid);
          const orig = this.btnCopySsid.textContent;
          this.btnCopySsid.textContent = '✓';
          setTimeout(() => (this.btnCopySsid.textContent = orig), 1500);
        }
      });
    }
  }

  async refreshHotspotStatus() {
    if (!window.skydrop || typeof window.skydrop.hotspotGetStatus !== 'function') return;
    try {
      const res = await window.skydrop.hotspotGetStatus();
      if (res && res.ok && res.data) {
        this.renderHotspotState(res.data);
      }
    } catch (_) {}
  }

  async handleHotspotToggle() {
    if (!window.skydrop) return;
    const isRunning = this.btnToggleHotspot.dataset.active === 'true';
    if (isRunning) {
      await this.stopHotspot();
    } else {
      await this.startHotspot(false);
    }
  }

  async startHotspot(disconnectCurrent) {
    if (this.hotspotErrorBox) this.hotspotErrorBox.style.display = 'none';
    if (this.btnHotspotDisconnect) this.btnHotspotDisconnect.style.display = 'none';

    this.btnToggleHotspot.disabled = true;
    this.btnToggleHotspot.textContent = 'Starting...';

    const timeoutSelect = document.getElementById('settings-hotspot-timeout');
    const idleTimeout = timeoutSelect ? parseInt(timeoutSelect.value, 10) || 10 : 10;

    try {
      const res = await window.skydrop.hotspotStart({
        disconnect_current: Boolean(disconnectCurrent),
        idle_timeout_minutes: idleTimeout
      });

      if (res && res.ok && res.data) {
        this.renderHotspotState(res.data);
      } else if (res && res.error === 'needs_disconnect') {
        this.showHotspotError(res.message || 'Your Wi-Fi adapter is currently connected. Disconnect to create hotspot?', true);
        this.renderHotspotState({ active: false });
      } else {
        const errMsg = (res && res.message) || (res && res.error) || 'Failed to start Wi-Fi hotspot';
        this.showHotspotError(errMsg, false);
        this.renderHotspotState({ active: false });
      }
    } catch (err) {
      this.showHotspotError(err.message || 'Error communicating with hotspot daemon', false);
      this.renderHotspotState({ active: false });
    } finally {
      this.btnToggleHotspot.disabled = false;
    }
  }

  async stopHotspot() {
    this.btnToggleHotspot.disabled = true;
    this.btnToggleHotspot.textContent = 'Stopping...';
    try {
      await window.skydrop.hotspotStop();
      this.renderHotspotState({ active: false });
    } catch (err) {
      this.showHotspotError('Failed to stop hotspot: ' + err.message, false);
    } finally {
      this.btnToggleHotspot.disabled = false;
    }
  }

  showHotspotError(msg, showDisconnectBtn) {
    if (this.hotspotErrorBox && this.hotspotErrorText) {
      this.hotspotErrorText.textContent = msg;
      this.hotspotErrorBox.style.display = 'block';
      if (this.btnHotspotDisconnect) {
        this.btnHotspotDisconnect.style.display = showDisconnectBtn ? 'inline-block' : 'none';
      }
    }
  }

  renderHotspotState(status) {
    if (!this.btnToggleHotspot) return;

    if (status && status.active) {
      this.btnToggleHotspot.dataset.active = 'true';
      this.btnToggleHotspot.textContent = 'Stop Hotspot';
      this.btnToggleHotspot.className = 'btn-danger';
      this.hotspotBadge.textContent = 'Active';
      this.hotspotBadge.className = 'badge badge-active';
      this.hotspotIcon.classList.add('active');
      this.hotspotSubtext.textContent = `Broadcasting on ${status.interface || 'Wi-Fi'}`;

      if (this.hotspotActivePanel) this.hotspotActivePanel.style.display = 'block';
      if (this.hotspotQrImg && status.qr_code) this.hotspotQrImg.src = status.qr_code;
      if (this.hotspotSsid) this.hotspotSsid.textContent = status.ssid || '';

      this.rawHotspotPassword = status.password || '';
      if (this.hotspotPassword) {
        this.hotspotPassword.textContent = this.passwordRevealed ? this.rawHotspotPassword : '••••••••••••';
      }
      if (this.hotspotIp) this.hotspotIp.textContent = status.ip || '10.42.0.1';

      const clients = status.connected_clients || [];
      if (this.hotspotClients) {
        if (clients.length === 0) {
          this.hotspotClients.textContent = '0 devices connected';
        } else {
          const ips = clients.map(c => c.ip || c.mac).filter(Boolean).join(', ');
          this.hotspotClients.textContent = `${clients.length} device(s) connected: ${ips}`;
        }
      }

      // Start polling
      if (!this.hotspotPollTimer) {
        this.hotspotPollTimer = setInterval(async () => {
          try {
            const res = await window.skydrop.hotspotGetStatus();
            if (res && res.ok && res.data) {
              const prevCount = (status.connected_clients || []).length;
              const newCount = (res.data.connected_clients || []).length;
              this.renderHotspotState(res.data);
              if (newCount !== prevCount) {
                this.app.pollPeers();
              }
            }
          } catch (_) {}
        }, 3000);
      }
    } else {
      this.btnToggleHotspot.dataset.active = 'false';
      this.btnToggleHotspot.textContent = 'Create Hotspot';
      this.btnToggleHotspot.className = 'btn-primary';
      this.hotspotBadge.textContent = 'Offline';
      this.hotspotBadge.className = 'badge badge-inactive';
      this.hotspotIcon.classList.remove('active');
      this.hotspotSubtext.textContent = 'Host a direct Wi-Fi network for offline peer transfers';

      if (this.hotspotActivePanel) this.hotspotActivePanel.style.display = 'none';

      if (this.hotspotPollTimer) {
        clearInterval(this.hotspotPollTimer);
        this.hotspotPollTimer = null;
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
