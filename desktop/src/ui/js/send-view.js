/**
 * Send view: handles file selection, drag-and-drop, target peer selection, and streaming progress.
 */
class SendView {
  constructor(app) {
    this.app = app;
    this.dropzone = document.getElementById('dropzone');
    this.queueContainer = document.getElementById('file-queue-container');
    this.targetSelect = document.getElementById('target-peer-select');
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
    // Click on dropzone to open native file dialog
    this.dropzone.addEventListener('click', () => this.browseFiles());

    // HTML5 Drag and Drop events
    this.dropzone.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.dropzone.classList.add('drag-active');
    });

    this.dropzone.addEventListener('dragleave', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.dropzone.classList.remove('drag-active');
    });

    this.dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.dropzone.classList.remove('drag-active');

      if (e.dataTransfer && e.dataTransfer.files) {
        const newFiles = [];
        for (let i = 0; i < e.dataTransfer.files.length; i++) {
          const file = e.dataTransfer.files[i];
          // In Electron, file.path contains the absolute filesystem path
          if (file.path) {
            newFiles.push({
              path: file.path,
              name: file.name,
              size: file.size
            });
          }
        }
        if (newFiles.length > 0) {
          this.addFiles(newFiles);
        }
      }
    });

    this.targetSelect.addEventListener('change', () => this.updateSendButtonState());

    this.sendBtn.addEventListener('click', () => this.sendQueue());

    // Listen for progress from main process
    window.skydrop.onTransferProgress((prog) => {
      this.progressCard.classList.add('active');
      this.progressFilename.textContent = `Sending: ${prog.filename}`;
      this.progressPercent.textContent = `${prog.percent}%`;
      this.progressBar.style.width = `${prog.percent}%`;
    });
    if (this.dismissErrorBtn) {
      this.dismissErrorBtn.addEventListener('click', () => this.hideError());
    }
  }

  async browseFiles() {
    const files = await window.skydrop.selectFiles();
    if (files && files.length > 0) {
      this.addFiles(files);
    }
  }

  addFiles(files) {
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
    if (this.queuedFiles.length === 0) {
      this.queueContainer.style.display = 'none';
      this.queueContainer.innerHTML = '';
      if (this.dropzone) {
        this.dropzone.classList.remove('compact');
      }
    } else {
      this.queueContainer.style.display = 'flex';
      if (this.dropzone) {
        this.dropzone.classList.add('compact');
      }

      const totalBytes = this.queuedFiles.reduce((sum, f) => sum + (f.size || 0), 0);
      const fileCountText = `${this.queuedFiles.length} file${this.queuedFiles.length === 1 ? '' : 's'} queued`;
      const sizeText = formatBytes(totalBytes);

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
              ${pres.isVideo ? '<div class="video-play-badge">▶</div>' : ''}
            </div>
            <div class="queue-card-meta">
              <div class="queue-card-name" title="${escapeHtml(f.name)}">${escapeHtml(f.name)}</div>
              <div class="queue-card-sub">
                <span class="file-format-pill" style="color: ${pres.accent}; background-color: ${pres.bgTint};">${displayLabel}</span>
                <span class="queue-card-size">${formatBytes(f.size)}</span>
              </div>
            </div>
            <button class="queue-btn-remove" title="Remove ${escapeHtml(f.name)}" onclick="window.sendView.removeFile(${idx})">✕</button>
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
                  ${pres.isVideo ? '<div class="video-play-badge">▶</div>' : ''}
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
    this.peers = (peersData && peersData.peers) || [];
    const currentVal = this.targetSelect.value;
    let optionsHtml = '<option value="">Choose target device...</option>';

    for (const p of this.peers) {
      const isOnline = p.is_connected;
      const isPaired = p.is_paired;
      const label = `${p.name} ${isOnline ? '(Online)' : '(Offline)'} ${isPaired ? '• Paired' : ''}`;
      optionsHtml += `<option value="${p.id}" ${p.id === currentVal ? 'selected' : ''} ${!isOnline ? 'disabled' : ''}>${escapeHtml(label)}</option>`;
    }

    if (this.targetSelect.innerHTML !== optionsHtml) {
      this.targetSelect.innerHTML = optionsHtml;
    }

    // Auto-select first online paired peer if none selected
    if (!this.targetSelect.value) {
      const activePeer = this.peers.find((p) => p.is_paired && p.is_connected) || this.peers.find((p) => p.is_connected);
      if (activePeer) {
        this.targetSelect.value = activePeer.id;
      }
    }
    this.updateSendButtonState();
  }

  updateSendButtonState() {
    const hasFiles = this.queuedFiles.length > 0;
    const hasTarget = Boolean(this.targetSelect.value);
    this.sendBtn.disabled = !hasFiles || !hasTarget || this.isSending;

    if (this.isSending) {
      this.sendBtn.textContent = 'Sending...';
    } else if (hasFiles && hasTarget) {
      const target = this.peers.find((p) => p.id === this.targetSelect.value);
      const name = target ? target.name : 'Device';
      this.sendBtn.textContent = `⚡ Drop ${this.queuedFiles.length} File(s) to ${name}`;
    } else {
      this.sendBtn.textContent = '⚡ Drop Files';
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
    const targetPeer = this.peers.find((p) => p.id === this.targetSelect.value);
    if (!targetPeer) return;

    this.hideError();
    this.isSending = true;
    this.updateSendButtonState();
    this.progressCard.classList.add('active');

    const targetUrl = targetPeer.baseUrl || `${targetPeer.is_tls ? 'https' : 'http'}://${targetPeer.host}:${targetPeer.port}`;
    const authToken = targetPeer.auth_token || '';

    try {
      for (const file of this.queuedFiles) {
        this.progressFilename.textContent = `Sending ${file.name}...`;
        const res = await window.skydrop.sendFile(file.path, targetUrl, authToken);
        if (!res.ok) {
          let friendly = 'Failed to drop file.';
          let title = 'Transfer Failed';
          const code = res.statusCode;
          const cat = res.category;
          const msg = res.error || '';

          if (code === 401 || code === 403 || cat === 'auth') {
            title = 'Not Paired / Unauthorized';
            friendly = 'Phone refused the file (Not paired or pairing token mismatch). Please pair device again.';
          } else if (code === 404) {
            title = 'Endpoint Not Found';
            friendly = 'Phone receiver service was not found or is inactive.';
          } else if (code === 413) {
            title = 'File Too Large';
            friendly = 'The recipient refused the file because it exceeds permitted storage size.';
          } else if (cat === 'connection_refused') {
            title = 'Phone Not Reachable';
            friendly = 'Connection refused. Ensure the SkyDrop receiver is running on the phone and Wi-Fi is active.';
          } else if (cat === 'timeout') {
            title = 'Transfer Timed Out';
            friendly = 'Connection to the phone timed out. Check network signal or reconnect to Wi-Fi.';
          } else if (cat === 'tls') {
            title = 'Security Handshake Error';
            friendly = 'TLS handshake or certificate verification failed.';
          } else if (msg) {
            friendly = msg;
          }

          const rawDetails = `Status Code: ${res.statusCode || 'N/A'}\nCategory: ${res.category || 'unknown'}\nTarget: ${res.targetUrl || targetUrl}\nError: ${res.error || 'Unknown error'}\n\nTechnical Details:\n${res.details || res.error || 'No further details'}`;
          console.error('[SkyDrop Send Error]', rawDetails);
          this.showError(title, friendly, rawDetails);
          this.progressFilename.textContent = `❌ ${title}`;
          return;
        }
      }
      this.queuedFiles = [];
      this.renderQueue();
      this.progressFilename.textContent = '✅ Transfer complete!';
      this.progressPercent.textContent = '100%';
      setTimeout(() => {
        this.progressCard.classList.remove('active');
        this.progressBar.style.width = '0%';
        if (this.app && typeof this.app.switchTab === 'function') {
          this.app.switchTab('screen-received');
        }
      }, 1500);
    } catch (err) {
      const errDetails = `Exception: ${err.message}\nStack: ${err.stack || ''}`;
      console.error('[SkyDrop Send Exception]', err);
      this.showError('Transfer Exception', err.message, errDetails);
      this.progressFilename.textContent = '❌ Transfer error';
    } finally {
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

window.SendView = SendView;
