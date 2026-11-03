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
            <button id="lightbox-close" class="lightbox-btn lightbox-btn-close" title="Close (Esc)">✕</button>
          </div>
        </div>
        <div id="lightbox-content" class="lightbox-content"></div>
        <button id="lightbox-prev" class="lightbox-nav-btn lightbox-nav-prev" title="Previous (Left Arrow)">❮</button>
        <button id="lightbox-next" class="lightbox-nav-btn lightbox-nav-next" title="Next (Right Arrow)">❯</button>
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

    this.prevBtn.addEventListener('click', () => this.prev());
    this.nextBtn.addEventListener('click', () => this.next());
    this.closeBtn.addEventListener('click', () => this.close());

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

    if (item.isVideo) {
      this.zoomControls.style.display = 'none';
      this.mediaContainer.innerHTML = `
        <video id="lightbox-video" class="lightbox-video" controls autoplay playsinline>
          <source src="${mediaUrl}">
          Your system cannot preview this video codec.
        </video>
      `;
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
    if (e.key === 'Escape') {
      this.close();
    } else if (e.key === 'ArrowLeft') {
      this.prev();
    } else if (e.key === 'ArrowRight') {
      this.next();
    } else if (e.key === '+' || e.key === '=') {
      this.setZoom(this.zoomLevel + 0.3);
    } else if (e.key === '-') {
      this.setZoom(this.zoomLevel - 0.3);
    } else if (e.key === '0') {
      this.setZoom(1.0);
    } else if (e.key === ' ' && this.items[this.currentIndex]?.isVideo) {
      const vid = this.mediaContainer.querySelector('video');
      if (vid) {
        e.preventDefault();
        if (vid.paused) vid.play();
        else vid.pause();
      }
    }
  }
}

window.Lightbox = Lightbox;
