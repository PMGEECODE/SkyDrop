/**
 * Send view: handles file selection, drag-and-drop, target peer selection, and streaming progress.
 */
class SendView {
  constructor(app) {
    this.app = app;
    this.screen = document.getElementById('screen-send');
    this.dropzone = document.getElementById('dropzone');
    this.queueContainer = document.getElementById('file-queue-container');
    this.targetSelect = document.getElementById('target-peer-select');
    this.customSelect = document.getElementById('custom-target-select');
    this.customSelectTrigger = document.getElementById('custom-select-trigger');
    this.customSelectLabel = document.getElementById('custom-select-label');
    this.customSelectMenu = document.getElementById('custom-select-menu');
    this.sendBtn = document.getElementById('btn-send-now');
    this.progressCard = document.getElementById('send-progress-card');
    this.progressFilename = document.getElementById('send-progress-filename');
    this.progressPercent = document.getElementById('send-progress-percent');
    this.progressBar = document.getElementById('send-progress-bar');
    this.errorCard = document.getElementById('send-error-card');
    this.errorTitle = document.getElementById('send-error-title');
    this.errorMessage = document.getElementById('send-error-message');
    this.errorRaw = document.getElementById('send-error-raw');
    this.dismissErrorBtn = document.getElementById('btn-dismiss-error');

    this.queuedFiles = [];
    this.isSending = false;
    this.peers = [];

    this.initEvents();
  }

  initEvents() {
    // Prevent default browser drag-and-drop behavior on document body
    document.addEventListener('dragover', (e) => e.preventDefault());
    document.addEventListener('drop', (e) => e.preventDefault());

    // Click on dropzone to open native file dialog
    if (this.dropzone) {
      this.dropzone.addEventListener('click', () => this.browseFiles());

      const processDroppedFiles = (files) => {
        if (!files || files.length === 0) return;
        const newFiles = [];
        for (let i = 0; i < files.length; i++) {
          const file = files[i];
          const filePath = file.path || (window.skydrop && window.skydrop.getPathForFile ? window.skydrop.getPathForFile(file) : '');
          if (filePath) {
            newFiles.push({
              path: filePath,
              name: file.name,
              size: file.size
            });
          }
        }
        if (newFiles.length > 0) {
          this.addFiles(newFiles);
        }
      };

      this.dropzone.addEventListener('dragenter', (e) => {
        e.preventDefault();
        this.dropzone.classList.add('drag-active');
      });

      this.dropzone.addEventListener('dragover', (e) => {
        e.preventDefault();
        if (e.dataTransfer) {
          e.dataTransfer.dropEffect = 'copy';
        }
        this.dropzone.classList.add('drag-active');
      });

      this.dropzone.addEventListener('dragleave', (e) => {
        e.preventDefault();
        if (!this.dropzone.contains(e.relatedTarget)) {
          this.dropzone.classList.remove('drag-active');
        }
      });

      this.dropzone.addEventListener('drop', (e) => {
        e.preventDefault();
        e.stopPropagation();
        this.dropzone.classList.remove('drag-active');
        if (e.dataTransfer && e.dataTransfer.files) {
          processDroppedFiles(e.dataTransfer.files);
        }
      });
    }

    // Support drag-and-drop onto the screen even when the top dropzone is hidden
    if (this.screen) {
      this.screen.addEventListener('dragover', (e) => {
        e.preventDefault();
        if (e.dataTransfer) {
          e.dataTransfer.dropEffect = 'copy';
        }
        if (this.queuedFiles.length > 0 && this.queueContainer) {
          this.queueContainer.classList.add('drag-active');
        }
      });

      this.screen.addEventListener('dragleave', (e) => {
        e.preventDefault();
        if (!this.screen.contains(e.relatedTarget)) {
          if (this.queueContainer) this.queueContainer.classList.remove('drag-active');
        }
      });

      this.screen.addEventListener('drop', (e) => {
        e.preventDefault();
        if (this.queueContainer) this.queueContainer.classList.remove('drag-active');
        if (e.dataTransfer && e.dataTransfer.files) {
          processDroppedFiles(e.dataTransfer.files);
        }
      });
    }

    if (this.customSelectTrigger) {
      this.customSelectTrigger.addEventListener('click', (e) => {
        e.stopPropagation();
        if (this.customSelect) {
          this.customSelect.classList.toggle('active');
        }
      });

      document.addEventListener('click', (e) => {
        if (this.customSelect && !this.customSelect.contains(e.target)) {
          this.customSelect.classList.remove('active');
        }
      });
    }

    if (this.targetSelect) {
      this.targetSelect.addEventListener('change', () => {
        this.renderCustomSelectLabel();
        this.updateSendButtonState();
      });
    }

    if (this.sendBtn) {
      this.sendBtn.addEventListener('click', () => this.sendQueue());
    }

    if (this.dismissErrorBtn) {
      this.dismissErrorBtn.addEventListener('click', () => this.hideError());
    }
  }

  async browseFiles() {
    const successCard = document.getElementById('send-success-card');
    if (successCard) successCard.style.display = 'none';

    const files = await window.skydrop.selectFiles();
    if (files && files.length > 0) {
      this.addFiles(files);
    }
  }

  addFiles(files) {
    const successCard = document.getElementById('send-success-card');
    if (successCard) successCard.style.display = 'none';

    for (const file of files) {
      if (!this.queuedFiles.some((f) => f.path === file.path)) {
        this.queuedFiles.push(file);
      }
    }
    this.renderQueue();
  }

  removeFile(index) {
    this.queuedFiles.splice(index, 1);
    this.renderQueue();
  }

  clearQueue() {
    this.queuedFiles = [];
    this.renderQueue();
  }

  renderQueue() {
    const warningEl = document.getElementById('large-queue-warning');
    const warningTextEl = document.getElementById('large-queue-warning-text');

    if (!this.queueContainer) return;

    if (this.queuedFiles.length === 0) {
      this.queueContainer.style.display = 'none';
      this.queueContainer.innerHTML = '';
      if (this.dropzone) {
        this.dropzone.style.display = 'flex';
        this.dropzone.classList.remove('compact');
      }
      if (warningEl) warningEl.style.display = 'none';
    } else {
      this.queueContainer.style.display = 'flex';
      if (this.dropzone) {
        this.dropzone.style.display = 'none';
        this.dropzone.classList.remove('compact');
      }

      const totalBytes = this.queuedFiles.reduce((sum, f) => sum + (f.size || 0), 0);
      const fileCountText = `${this.queuedFiles.length} file${this.queuedFiles.length === 1 ? '' : 's'} queued`;
      const sizeText = formatBytes(totalBytes);

      // Large Queue Size Warning (>100MB)
      if (warningEl) {
        if (totalBytes > 100 * 1024 * 1024) {
          warningEl.style.display = 'flex';
          if (warningTextEl) {
            warningTextEl.textContent = `Total size: ${sizeText} across ${this.queuedFiles.length} file(s). Ensure recipient has enough free disk space.`;
          }
        } else {
          warningEl.style.display = 'none';
        }
      }

      let html = `
        <div class="queue-toolbar">
          <div class="queue-toolbar-left">
            <span class="queue-count-badge">${this.queuedFiles.length}</span>
            <span class="queue-summary-text">${fileCountText} • ${sizeText}</span>
          </div>
          <div class="queue-toolbar-actions">
            <button class="btn-tiny" id="btn-add-more-files" title="Add more files">+ Add</button>
            <button class="btn-tiny btn-tiny-danger" id="btn-clear-queue" title="Clear all queued files">Clear</button>
          </div>
        </div>
        <div class="queue-items-list">
      `;

      this.queuedFiles.forEach((f, idx) => {
        const pres = typeof getFileTypePresentation === 'function'
          ? getFileTypePresentation(f.name)
          : (typeof getFilePresentation === 'function' ? getFilePresentation(f.name) : { svg: '', label: 'FILE', accent: '#64748b', bgTint: 'rgba(100,116,139,0.12)' });

        const thumbId = `send-queue-thumb-${idx}`;
        const ext = f.name.split('.').pop().toUpperCase();
        const displayLabel = pres.label || ext || 'FILE';

        html += `
          <div class="queue-card" id="queue-item-${idx}">
            <div class="queue-thumb-box" id="${thumbId}" style="background-color: ${pres.bgTint || 'rgba(100,116,139,0.12)'};">
              <div class="queue-thumb-icon" style="color: ${pres.accent || 'var(--color-primary)'};">
                ${pres.svg || (typeof SVG_ICONS !== 'undefined' ? SVG_ICONS.generic : '')}
              </div>
              <span class="queue-thumb-tag" style="background-color: ${pres.accent || 'var(--color-primary)'};">${displayLabel}</span>
              ${pres.isVideo ? '<div class="video-play-badge"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg></div>' : ''}
            </div>
            <div class="queue-card-meta">
              <div class="queue-card-name" title="${escapeHtml(f.name)}">${escapeHtml(f.name)}</div>
              <div class="queue-card-sub">
                <span class="file-format-pill" style="color: ${pres.accent}; background-color: ${pres.bgTint};">${displayLabel}</span>
                <span class="queue-card-size">${formatBytes(f.size)}</span>
              </div>
            </div>
            <button class="queue-btn-remove" title="Remove ${escapeHtml(f.name)}" onclick="window.sendView.removeFile(${idx})"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg></button>
          </div>
        `;
      });

      html += `</div>`;
      this.queueContainer.innerHTML = html;

      // Event listeners for Add and Clear
      const addMoreBtn = document.getElementById('btn-add-more-files');
      if (addMoreBtn) {
        addMoreBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          this.browseFiles();
        });
      }

      const clearBtn = document.getElementById('btn-clear-queue');
      if (clearBtn) {
        clearBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          this.clearQueue();
        });
      }

      // Asynchronously fetch thumbnails for visual media (images & videos)
      this.queuedFiles.forEach((f, idx) => {
        const pres = typeof getFileTypePresentation === 'function' ? getFileTypePresentation(f.name) : {};
        if (pres.isVisual && f.path && window.skydrop && window.skydrop.getFileThumbnail) {
          const thumbId = `send-queue-thumb-${idx}`;
          window.skydrop.getFileThumbnail(f.path).then((dataUrl) => {
            if (dataUrl) {
              const el = document.getElementById(thumbId);
              if (el) {
                el.innerHTML = `
                  <img class="media-thumb-img" src="${dataUrl}" alt="thumbnail" loading="lazy">
                  <span class="queue-thumb-tag" style="background-color: ${pres.accent || 'var(--color-primary)'};">${pres.label || ''}</span>
                  ${pres.isVideo ? '<div class="video-play-badge"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg></div>' : ''}
                `;
              }
            }
          }).catch(() => {});
        }
      });
    }
    this.updateSendButtonState();
  }

  updatePeers(peersData) {
    if (!this.targetSelect) return;
    this.peers = (peersData && peersData.peers) || [];
    const currentVal = this.targetSelect.value;

    let optionsHtml = '<option value="">Choose target device...</option>';
    let menuHtml = '';

    const onlinePeers = this.peers.filter((p) => p.is_connected);
    if (onlinePeers.length > 1) {
      const isAllSelected = currentVal === '__ALL__';
      optionsHtml += `<option value="__ALL__" ${isAllSelected ? 'selected' : ''}>✨ All Connected Devices (${onlinePeers.length})</option>`;
      menuHtml += `
        <div class="custom-select-option ${isAllSelected ? 'selected' : ''}" data-value="__ALL__" data-online="true">
          <span>✨ All Connected Devices (${onlinePeers.length})</span>
          <span class="option-badge online">Broadcast</span>
        </div>
      `;
    }

    for (const p of this.peers) {
      const isOnline = p.is_connected;
      const isPaired = p.is_paired;
      const isSelected = p.id === currentVal;
      const label = `${p.name} ${isOnline ? '(Online)' : '(Offline)'} ${isPaired ? '• Paired' : ''}`;

      optionsHtml += `<option value="${p.id}" ${isSelected ? 'selected' : ''} ${!isOnline ? 'disabled' : ''}>${escapeHtml(label)}</option>`;

      const badgeClass = isOnline ? 'online' : 'offline';
      const badgeText = isOnline ? (isPaired ? 'Paired' : 'Online') : 'Offline';

      menuHtml += `
        <div class="custom-select-option ${isSelected ? 'selected' : ''} ${!isOnline ? 'disabled' : ''}" data-value="${p.id}" data-online="${isOnline}">
          <span>${escapeHtml(p.name)}</span>
          <span class="option-badge ${badgeClass}">${badgeText}</span>
        </div>
      `;
    }

    if (this.peers.length === 0) {
      menuHtml = '<div class="custom-select-option disabled" style="color: var(--text-muted);">Searching for nearby devices...</div>';
    }

    if (this.targetSelect.innerHTML !== optionsHtml) {
      this.targetSelect.innerHTML = optionsHtml;
    }

    if (this.customSelectMenu) {
      this.customSelectMenu.innerHTML = menuHtml;

      this.customSelectMenu.querySelectorAll('.custom-select-option[data-value]').forEach((opt) => {
        opt.addEventListener('click', (e) => {
          e.stopPropagation();
          const val = opt.getAttribute('data-value');
          const isOnline = opt.getAttribute('data-online') === 'true';
          if (!isOnline) return;

          this.targetSelect.value = val;
          if (this.customSelect) this.customSelect.classList.remove('active');
          this.renderCustomSelectLabel();
          this.updateSendButtonState();
        });
      });
    }

    // Auto-select first online paired peer if none selected
    if (!this.targetSelect.value) {
      const activePeer = this.peers.find((p) => p.is_paired && p.is_connected) || this.peers.find((p) => p.is_connected);
      if (activePeer) {
        this.targetSelect.value = activePeer.id;
      }
    }

    this.renderCustomSelectLabel();
    this.updateSendButtonState();
  }

  renderCustomSelectLabel() {
    if (!this.customSelectLabel) return;
    if (this.targetSelect.value === '__ALL__') {
      const onlineCount = this.peers.filter((p) => p.is_connected).length;
      this.customSelectLabel.textContent = `✨ All Connected Devices (${onlineCount})`;
      return;
    }
    const selectedPeer = this.peers.find((p) => p.id === this.targetSelect.value);
    if (selectedPeer) {
      this.customSelectLabel.textContent = selectedPeer.name;
    } else {
      this.customSelectLabel.textContent = 'Choose target device...';
    }
  }

  updateSendButtonState() {
    if (!this.sendBtn) return;
    const hasFiles = this.queuedFiles.length > 0;
    const hasTarget = Boolean(this.targetSelect.value);
    this.sendBtn.disabled = !hasFiles || !hasTarget || this.isSending;

    if (this.isSending) {
      this.sendBtn.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg> Sending...';
    } else if (hasFiles && hasTarget) {
      if (this.targetSelect.value === '__ALL__') {
        const onlineCount = this.peers.filter((p) => p.is_connected).length;
        this.sendBtn.innerHTML = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg> Drop ${this.queuedFiles.length} File(s) to All Devices (${onlineCount})`;
      } else {
        const target = this.peers.find((p) => p.id === this.targetSelect.value);
        const name = target ? target.name : 'Device';
        this.sendBtn.innerHTML = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg> Drop ${this.queuedFiles.length} File(s) to ${escapeHtml(name)}`;
      }
    } else {
      this.sendBtn.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg> Drop Files';
    }
  }

  showError(title, friendlyMsg, technicalDetails) {
    if (!this.errorCard) return;
    if (this.errorTitle) this.errorTitle.textContent = title || 'Transfer Failed';
    if (this.errorMessage) this.errorMessage.textContent = friendlyMsg || 'An error occurred during transfer.';
    if (this.errorRaw) this.errorRaw.textContent = technicalDetails || '';
    this.errorCard.style.display = 'block';
  }

  hideError() {
    if (this.errorCard) {
      this.errorCard.style.display = 'none';
      if (this.errorRaw) this.errorRaw.textContent = '';
    }
  }

  async sendQueue() {
    if (this.isSending || this.queuedFiles.length === 0) return;

    let targetPeers = [];
    if (this.targetSelect.value === '__ALL__') {
      targetPeers = this.peers.filter((p) => p.is_connected);
    } else {
      const p = this.peers.find((p) => p.id === this.targetSelect.value);
      if (p) targetPeers = [p];
    }
    if (targetPeers.length === 0) return;

    const progressStatsEl = document.getElementById('send-progress-stats');
    const progressEtaEl = document.getElementById('send-progress-eta');
    const successCard = document.getElementById('send-success-card');
    const successSub = document.getElementById('send-success-sub');
    const sendMoreBtn = document.getElementById('btn-send-more');

    if (successCard) successCard.style.display = 'none';

    this.hideError();
    this.isSending = true;
    this.updateSendButtonState();
    if (this.progressCard) this.progressCard.classList.add('active');

    const startTime = Date.now();
    const completedCount = this.queuedFiles.length;

    // Listen for real-time streaming progress
    let unsub = null;
    if (window.skydrop && typeof window.skydrop.onTransferProgress === 'function') {
      unsub = window.skydrop.onTransferProgress((prog) => {
        if (!prog) return;
        if (this.progressCard) this.progressCard.classList.add('active');
        if (this.progressFilename) this.progressFilename.textContent = `Sending ${prog.filename || 'file'}...`;

        const pct = prog.percent || 0;
        if (this.progressPercent) this.progressPercent.textContent = `${pct}%`;
        if (this.progressBar) this.progressBar.style.width = `${pct}%`;

        const elapsedSec = (Date.now() - startTime) / 1000;
        const currentBytes = prog.bytesTransferred || 0;
        const currentTotal = prog.totalBytes || 0;

        if (elapsedSec > 0.2) {
          const speedBps = currentBytes / elapsedSec;
          const speedMb = (speedBps / (1024 * 1024)).toFixed(1);
          const remainingBytes = Math.max(0, currentTotal - currentBytes);
          const etaSec = speedBps > 0 ? Math.ceil(remainingBytes / speedBps) : 0;

          if (progressStatsEl) {
            progressStatsEl.textContent = `${formatBytes(currentBytes)} / ${formatBytes(currentTotal)} • ${speedMb} MB/s`;
          }
          if (progressEtaEl) {
            progressEtaEl.textContent = `ETA ${etaSec < 10 ? '0' : ''}${etaSec}s`;
          }
        }
      });
    }

    try {
      for (let i = 0; i < targetPeers.length; i++) {
        const targetPeer = targetPeers[i];
        const targetUrl = targetPeer.baseUrl || `${targetPeer.is_tls ? 'https' : 'http'}://${targetPeer.host}:${targetPeer.port}`;
        const authToken = targetPeer.auth_token || '';
        const devPrefix = targetPeers.length > 1 ? `[${i + 1}/${targetPeers.length} to ${targetPeer.name}] ` : '';

        for (const file of this.queuedFiles) {
          if (this.progressFilename) this.progressFilename.textContent = `${devPrefix}Sending ${file.name}...`;
          const res = await window.skydrop.sendFile(file.path, targetUrl, authToken);
          if (!res.ok) {
            let friendly = 'Failed to drop file.';
            let title = 'Transfer Failed';
            const code = res.statusCode;
            const cat = res.category;
            const msg = res.error || '';

            if (code === 401 || code === 403 || cat === 'auth') {
              title = 'Not Paired / Unauthorized';
              friendly = 'Target device refused the file (Not paired or pairing token mismatch). Please pair device again.';
            } else if (code === 404) {
              title = 'Endpoint Not Found';
              friendly = 'Target receiver service was not found or is inactive.';
            } else if (code === 413) {
              title = 'File Too Large';
              friendly = 'The recipient refused the file because it exceeds permitted storage size.';
            } else if (cat === 'connection_refused') {
              title = 'Device Not Reachable';
              friendly = `Connection refused for ${targetPeer.name}. Ensure the SkyDrop receiver is running and connected to Wi-Fi.`;
            } else if (cat === 'timeout') {
              title = 'Transfer Timed Out';
              friendly = `Connection to ${targetPeer.name} timed out. Check network signal or reconnect to Wi-Fi.`;
            } else if (cat === 'tls') {
              title = 'Security Handshake Error';
              friendly = 'TLS handshake or certificate verification failed.';
            } else if (msg) {
              friendly = msg;
            }

            const rawDetails = `Status Code: ${res.statusCode || 'N/A'}\nCategory: ${res.category || 'unknown'}\nTarget: ${res.targetUrl || targetUrl}\nError: ${res.error || 'Unknown error'}\n\nTechnical Details:\n${res.details || res.error || 'No further details'}`;
            console.error('[SkyDrop Send Error]', rawDetails);
            this.showError(title, friendly, rawDetails);
            if (this.progressFilename) this.progressFilename.innerHTML = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="var(--color-danger)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg> ${title}`;
            return;
          }
        }
      }

      // Success: Clear queue and render post-send completion state
      this.queuedFiles = [];
      this.renderQueue();

      if (this.progressCard) this.progressCard.classList.remove('active');

      if (successCard) {
        if (successSub) {
          const targetName = targetPeers.length > 1 ? `all ${targetPeers.length} devices` : escapeHtml(targetPeers[0].name);
          successSub.textContent = `✓ ${completedCount} file${completedCount === 1 ? '' : 's'} sent to ${targetName}`;
        }
        successCard.style.display = 'flex';

        if (sendMoreBtn) {
          sendMoreBtn.onclick = () => {
            successCard.style.display = 'none';
            this.browseFiles();
          };
        }
      }

      if (this.app && typeof this.app.switchTab === 'function') {
        setTimeout(() => this.app.switchTab('screen-received'), 2500);
      }
    } catch (err) {
      const errDetails = `Exception: ${err.message}\nStack: ${err.stack || ''}`;
      console.error('[SkyDrop Send Exception]', err);
      this.showError('Transfer Exception', err.message, errDetails);
      if (this.progressFilename) this.progressFilename.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="var(--color-danger)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg> Transfer error';
    } finally {
      if (typeof unsub === 'function') unsub();
      this.isSending = false;
      this.updateSendButtonState();
      this.app.refreshHistory();
    }
  }
}

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

window.SendView = SendView;
