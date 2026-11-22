/**
 * Quick Preview Lightbox for Images and Videos.
 * Supports zoom, pan, next/previous keyboard shortcuts, and full video playback.
 */
class Lightbox {
  constructor() {
    this.modal = null;
    this.mediaContainer = null;
    this.titleEl = null;
    this.counterEl = null;
    this.prevBtn = null;
    this.nextBtn = null;
    this.closeBtn = null;
    this.zoomControls = null;

    this.items = [];
    this.currentIndex = 0;
    this.zoomLevel = 1.0;
    this.isPanning = false;
    this.panStart = { x: 0, y: 0 };
    this.panOffset = { x: 0, y: 0 };
    this.boundKeyHandler = this.handleKeyDown.bind(this);

    this.initDOM();
  }

  initDOM() {
    let el = document.getElementById('lightbox-modal');
    if (!el) {
      el = document.createElement('div');
      el.id = 'lightbox-modal';
      el.className = 'lightbox-modal';
      el.innerHTML = `
        <div class="lightbox-header">
          <div class="lightbox-info">
            <span id="lightbox-title" class="lightbox-title"></span>
            <span id="lightbox-counter" class="lightbox-counter"></span>
          </div>
          <div class="lightbox-actions">
            <div id="lightbox-zoom-controls" class="lightbox-zoom-group">
              <button id="lightbox-zoom-out" class="lightbox-btn" title="Zoom Out (-)">−</button>
              <button id="lightbox-zoom-reset" class="lightbox-btn" title="Reset Zoom (0)">100%</button>
              <button id="lightbox-zoom-in" class="lightbox-btn" title="Zoom In (+)">+</button>
            </div>
            <button id="lightbox-open-external" class="lightbox-btn" title="Open in external player"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path><polyline points="15 3 21 3 21 9"></polyline><line x1="10" y1="14" x2="21" y2="3"></line></svg></button>
            <button id="lightbox-close" class="lightbox-btn lightbox-btn-close" title="Close (Esc)"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg></button>
          </div>
        </div>
        <div id="lightbox-content" class="lightbox-content"></div>
        <button id="lightbox-prev" class="lightbox-nav-btn lightbox-nav-prev" title="Previous (Left Arrow)"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg></button>
        <button id="lightbox-next" class="lightbox-nav-btn lightbox-nav-next" title="Next (Right Arrow)"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg></button>
      `;
      document.body.appendChild(el);
    }

    this.modal = el;
    this.mediaContainer = el.querySelector('#lightbox-content');
    this.titleEl = el.querySelector('#lightbox-title');
    this.counterEl = el.querySelector('#lightbox-counter');
    this.prevBtn = el.querySelector('#lightbox-prev');
    this.nextBtn = el.querySelector('#lightbox-next');
    this.closeBtn = el.querySelector('#lightbox-close');
    this.zoomControls = el.querySelector('#lightbox-zoom-controls');
    this.openExternalBtn = el.querySelector('#lightbox-open-external');

    this.prevBtn.addEventListener('click', () => this.prev());
    this.nextBtn.addEventListener('click', () => this.next());
    this.closeBtn.addEventListener('click', () => this.close());

    if (this.openExternalBtn) {
      this.openExternalBtn.addEventListener('click', () => {
        const item = this.items[this.currentIndex];
        if (item && item.saved_path && window.skydrop && window.skydrop.openPath) {
          window.skydrop.openPath(item.saved_path);
        }
      });
    }

    el.querySelector('#lightbox-zoom-in').addEventListener('click', () => this.setZoom(this.zoomLevel + 0.3));
    el.querySelector('#lightbox-zoom-out').addEventListener('click', () => this.setZoom(this.zoomLevel - 0.3));
    el.querySelector('#lightbox-zoom-reset').addEventListener('click', () => this.setZoom(1.0));

    // Close on background backdrop click
    this.mediaContainer.addEventListener('click', (e) => {
      if (e.target === this.mediaContainer) {
        this.close();
      }
    });

    // Mouse wheel zoom
    this.mediaContainer.addEventListener('wheel', (e) => {
      const img = this.mediaContainer.querySelector('img');
      if (img) {
        e.preventDefault();
        const delta = e.deltaY < 0 ? 0.2 : -0.2;
        this.setZoom(this.zoomLevel + delta);
      }
    }, { passive: false });
  }

  open(items, startIndex = 0) {
    if (!items || items.length === 0) return;
    this.items = items;
    this.currentIndex = Math.max(0, Math.min(startIndex, items.length - 1));
    this.modal.classList.add('active');
    document.addEventListener('keydown', this.boundKeyHandler);
    this.renderCurrent();
  }

  close() {
    this.modal.classList.remove('active');
    document.removeEventListener('keydown', this.boundKeyHandler);
    this.mediaContainer.innerHTML = '';
    this.items = [];
    this.zoomLevel = 1.0;
  }

  prev() {
    if (this.currentIndex > 0) {
      this.currentIndex--;
      this.renderCurrent();
    }
  }

  next() {
    if (this.currentIndex < this.items.length - 1) {
      this.currentIndex++;
      this.renderCurrent();
    }
  }

  setZoom(level) {
    this.zoomLevel = Math.max(0.5, Math.min(level, 4.0));
    const img = this.mediaContainer.querySelector('img');
    const resetBtn = this.modal.querySelector('#lightbox-zoom-reset');
    if (img) {
      img.style.transform = `scale(${this.zoomLevel}) translate(${this.panOffset.x}px, ${this.panOffset.y}px)`;
    }
    if (resetBtn) {
      resetBtn.textContent = `${Math.round(this.zoomLevel * 100)}%`;
    }
  }

  renderCurrent() {
    const item = this.items[this.currentIndex];
    if (!item) return;

    this.titleEl.textContent = item.filename;
    this.counterEl.textContent = `${this.currentIndex + 1} / ${this.items.length}`;

    this.prevBtn.style.visibility = this.currentIndex > 0 ? 'visible' : 'hidden';
    this.nextBtn.style.visibility = this.currentIndex < this.items.length - 1 ? 'visible' : 'hidden';

    this.zoomLevel = 1.0;
    this.panOffset = { x: 0, y: 0 };
    this.modal.querySelector('#lightbox-zoom-reset').textContent = '100%';

    const mediaUrl = `skydrop-media://local/${encodeURIComponent(item.saved_path)}`;
    const pres = typeof window.getFileTypePresentation === 'function' ? window.getFileTypePresentation(item.filename) : {};

    const isVid = item.isVideo || pres.isVideo;
    const isAud = item.isAudio || pres.isAudio;

    const renderFallback = (message) => {
      this.mediaContainer.innerHTML = `
        <div class="media-fallback-card" style="background: var(--bg-surface); padding: 36px 40px; border-radius: 16px; border: 1px solid var(--border-color); display: flex; flex-direction: column; align-items: center; gap: 14px; box-shadow: var(--shadow-lg); text-align: center; max-width: 440px; margin: auto;">
          <div style="font-size: 38px;">⚠️</div>
          <div style="font-size: 16px; font-weight: 700; color: var(--text-main);">Cannot Play This Format</div>
          <div style="font-size: 13px; color: var(--text-muted); line-height: 1.5;">${message || 'The format or codec cannot be rendered in-app. You can open it in your system media player.'}</div>
          <button id="lightbox-fallback-btn" class="btn-primary" style="margin-top: 8px; padding: 10px 20px; font-size: 13px; border-radius: 8px; cursor: pointer;">Open in Default Player</button>
        </div>
      `;
      const btn = this.mediaContainer.querySelector('#lightbox-fallback-btn');
      if (btn) {
        btn.addEventListener('click', () => {
          if (item && item.saved_path && window.skydrop && window.skydrop.openPath) {
            window.skydrop.openPath(item.saved_path);
          }
        });
      }
    };

    if (isVid) {
      this.zoomControls.style.display = 'none';
      this.mediaContainer.innerHTML = `
        <video id="lightbox-video" class="lightbox-video" controls autoplay playsinline style="max-width: 90vw; max-height: 80vh; border-radius: 8px; outline: none; box-shadow: var(--shadow-lg);">
          <source src="${mediaUrl}">
        </video>
      `;
      const vid = this.mediaContainer.querySelector('#lightbox-video');
      if (vid) {
        vid.addEventListener('error', () => {
          renderFallback('Video codec or format is not supported by the in-app player.');
        });
      }
    } else if (isAud) {
      this.zoomControls.style.display = 'none';
      this.mediaContainer.innerHTML = `
        <div class="audio-preview-card" style="background: var(--bg-surface); padding: 32px 40px; border-radius: 16px; border: 1px solid var(--border-color); display: flex; flex-direction: column; align-items: center; gap: 16px; box-shadow: var(--shadow-lg); text-align: center; max-width: 380px; margin: auto;">
          <div style="width: 72px; height: 72px; border-radius: 50%; background: rgba(6, 182, 212, 0.15); color: #06b6d4; display: flex; align-items: center; justify-content: center;">
            <svg viewBox="0 0 24 24" width="38" height="38" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
          </div>
          <div>
            <div style="font-size: 14px; font-weight: 700; color: var(--text-main); margin-bottom: 4px; word-break: break-all;">${item.filename}</div>
            <div style="font-size: 11px; color: var(--text-muted);">${typeof formatBytes === 'function' ? formatBytes(item.size || 0) : ''}</div>
          </div>
          <audio id="lightbox-audio" controls autoplay style="width: 100%; max-width: 280px; margin-top: 8px; outline: none;">
            <source src="${mediaUrl}">
          </audio>
        </div>
      `;
      const aud = this.mediaContainer.querySelector('#lightbox-audio');
      if (aud) {
        aud.addEventListener('error', () => {
          renderFallback('Audio format is not supported by the in-app player.');
        });
      }
    } else {
      this.zoomControls.style.display = 'flex';
      this.mediaContainer.innerHTML = `
        <img id="lightbox-img" class="lightbox-image" src="${mediaUrl}" alt="${item.filename}" draggable="false">
      `;

      const img = this.mediaContainer.querySelector('#lightbox-img');
      this.setupImagePan(img);
    }
  }

  setupImagePan(img) {
    let startX = 0;
    let startY = 0;

    img.addEventListener('mousedown', (e) => {
      if (this.zoomLevel <= 1.0) return;
      this.isPanning = true;
      startX = e.clientX - this.panOffset.x;
      startY = e.clientY - this.panOffset.y;
      img.style.cursor = 'grabbing';
      e.preventDefault();
    });

    window.addEventListener('mousemove', (e) => {
      if (!this.isPanning) return;
      this.panOffset.x = (e.clientX - startX);
      this.panOffset.y = (e.clientY - startY);
      img.style.transform = `scale(${this.zoomLevel}) translate(${this.panOffset.x / this.zoomLevel}px, ${this.panOffset.y / this.zoomLevel}px)`;
    });

    window.addEventListener('mouseup', () => {
      if (this.isPanning) {
        this.isPanning = false;
        if (img) img.style.cursor = this.zoomLevel > 1.0 ? 'grab' : 'default';
      }
    });
  }

  handleKeyDown(e) {
    const vid = this.mediaContainer.querySelector('video');
    const aud = this.mediaContainer.querySelector('audio');
    const media = vid || aud;

    if (e.key === 'Escape') {
      this.close();
    } else if (e.key === 'ArrowLeft') {
      if (media && !e.shiftKey) {
        e.preventDefault();
        media.currentTime = Math.max(0, media.currentTime - 10);
      } else {
        this.prev();
      }
    } else if (e.key === 'ArrowRight') {
      if (media && !e.shiftKey) {
        e.preventDefault();
        media.currentTime = Math.min(media.duration || 0, media.currentTime + 10);
      } else {
        this.next();
      }
    } else if (e.key === '+' || e.key === '=') {
      this.setZoom(this.zoomLevel + 0.3);
    } else if (e.key === '-') {
      this.setZoom(this.zoomLevel - 0.3);
    } else if (e.key === '0') {
      this.setZoom(1.0);
    } else if (e.key === ' ' || e.key === 'Enter') {
      if (media) {
        e.preventDefault();
        if (media.paused) media.play();
        else media.pause();
      }
    }
  }
}

window.Lightbox = Lightbox;
