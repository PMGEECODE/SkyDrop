/**
 * SkyDrop Desktop Received Files View
 * Features:
 * - Responsive CSS Grid (auto-fill, minmax(--tile-min, 1fr))
 * - Density presets (compact, comfortable, large)
 * - Grid/List view toggle
 * - Sticky date grouping (Today, Yesterday, This week, Earlier)
 * - Filter chips (All, Images, Videos, Documents, Audio, Archives, Other)
 * - Sort options (Newest, Name, Size) and live name search
 * - IntersectionObserver lazy thumbnail loading with skeleton placeholders
 * - Quick preview lightbox for images and videos
 * - Full-featured context menu with "Delete from disk" option
 * - Multi-select mode with floating action toolbar
 * - Native OS drag-out of files
 * - Spatial keyboard navigation (arrows, Enter, Space, Delete)
 */

class ReceivedView {
  constructor(app) {
    this.app = app;
    this.rawEntries = [];
    this.filteredEntries = [];
    this.groupedEntries = new Map();

    // Preferences & Filter State
    this.viewMode = localStorage.getItem('skydrop-received-view') || 'grid';
    this.density = localStorage.getItem('skydrop-received-density') || 'comfortable';
    this.activeFilter = 'all';
    this.searchQuery = '';
    this.sortBy = 'newest';

    // Multi-selection state
    this.isSelectMode = false;
    this.selectedIds = new Set();
    this.lastSelectedId = null;

    // Thumbnail lazy observer & cache tracking
    this.observer = null;
    this.renderedThumbs = new Map(); // path -> dataUrl

    // DOM References
    this.container = document.getElementById('received-list-container');
    this.badge = document.getElementById('received-badge');
    this.contextMenu = document.getElementById('context-menu');
    this.activeContextItem = null;

    // Receive Progress Banner Elements
    this.progressCard = document.getElementById('receive-progress-card');
    this.progressFilename = document.getElementById('receive-progress-filename');
    this.progressMeta = document.getElementById('receive-progress-meta');
    this.progressPercent = document.getElementById('receive-progress-percent');
    this.progressBar = document.getElementById('receive-progress-bar');
    this.progressDismissTimer = null;

    // Initialize Lightbox
    this.lightbox = new window.Lightbox();

    this.initObserver();
    this.initControls();
    this.initContextMenu();
    this.initKeyboardNav();
    this.initReceiveProgressListener();
    this.applyDensity();
  }

  initReceiveProgressListener() {
    if (window.skydrop && typeof window.skydrop.onReceiveProgress === 'function') {
      window.skydrop.onReceiveProgress((prog) => {
        this.updateReceiveProgress(prog);
      });
    }
  }

  updateReceiveProgress(prog) {
    if (!prog || !this.progressCard) return;

    if (this.progressDismissTimer) {
      clearTimeout(this.progressDismissTimer);
      this.progressDismissTimer = null;
    }

    if (prog.status === 'receiving') {
      this.progressCard.classList.add('active');
      if (this.progressFilename) {
        this.progressFilename.textContent = `Receiving: ${prog.filename || 'File'}`;
      }
      const transferredStr = typeof formatBytes === 'function' ? formatBytes(prog.bytes_received || 0) : `${prog.bytes_received} B`;
      const totalStr = typeof formatBytes === 'function' ? formatBytes(prog.total_bytes || 0) : `${prog.total_bytes} B`;
      const pct = Math.min(100, Math.max(0, prog.percent || 0));

      if (this.progressMeta) {
        this.progressMeta.textContent = `${pct}% (${transferredStr} / ${totalStr})`;
      }
      if (this.progressPercent) {
        this.progressPercent.textContent = `${pct}%`;
      }
      if (this.progressBar) {
        this.progressBar.style.width = `${pct}%`;
      }
    } else if (prog.status === 'completed') {
      this.progressCard.classList.add('active');
      if (this.progressFilename) {
        this.progressFilename.textContent = `Received: ${prog.filename || 'File'}`;
        this.progressFilename.innerHTML = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="var(--color-success)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg> Received: ${escapeHtml(prog.filename || 'File')}`;
      }
      if (this.progressPercent) {
        this.progressPercent.textContent = '100%';
      }
      if (this.progressBar) {
        this.progressBar.style.width = '100%';
      }
      // Refresh history to display the new item promptly
      if (this.app) {
        this.app.refreshHistory();
      }
      this.progressDismissTimer = setTimeout(() => {
        this.progressCard.classList.remove('active');
        if (this.progressBar) this.progressBar.style.width = '0%';
      }, 2500);
    } else if (prog.status === 'failed') {
      if (this.progressFilename) {
        this.progressFilename.textContent = `Failed receiving: ${prog.filename || 'File'}`;
        this.progressFilename.innerHTML = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="var(--color-danger)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle; margin-right: 4px;"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg> Failed receiving: ${escapeHtml(prog.filename || 'File')}`;
      }
      this.progressDismissTimer = setTimeout(() => {
        this.progressCard.classList.remove('active');
      }, 3000);
    }
  }

  initObserver() {
    this.observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          const tile = entry.target;
          const filePath = tile.getAttribute('data-filepath');
          const isVisual = tile.getAttribute('data-visual') === 'true';
          const isVideo = tile.getAttribute('data-video') === 'true';

          if (isVisual && filePath) {
            this.loadThumbnail(tile, filePath, isVideo);
          }
          this.observer.unobserve(tile);
        }
      });
    }, { root: this.container, rootMargin: '100px' });
  }

  applyDensity() {
    const densities = {
      compact: '84px',
      comfortable: '112px',
      large: '148px'
    };
    const val = densities[this.density] || '112px';
    document.documentElement.style.setProperty('--tile-min', val);
  }

  initControls() {
    // Toolbar search
    const searchInput = document.getElementById('received-search');
    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        this.searchQuery = e.target.value.trim().toLowerCase();
        this.applyFilterAndRender();
      });
    }

    // Filter chips
    const chips = document.querySelectorAll('.filter-chip');
    chips.forEach((chip) => {
      chip.addEventListener('click', () => {
        chips.forEach((c) => c.classList.remove('active'));
        chip.classList.add('active');
        this.activeFilter = chip.getAttribute('data-category') || 'all';
        this.applyFilterAndRender();
      });
    });

    // Custom Sort selector
    const sortSelect = document.getElementById('received-sort');
    const customSort = document.getElementById('custom-sort-select');
    const customSortTrigger = document.getElementById('custom-sort-trigger');
    const customSortLabel = document.getElementById('custom-sort-label');
    const customSortMenu = document.getElementById('custom-sort-menu');

    if (customSortTrigger && customSortMenu) {
      customSortTrigger.addEventListener('click', (e) => {
        e.stopPropagation();
        if (customSort) customSort.classList.toggle('active');
      });

      customSortMenu.querySelectorAll('.custom-select-option[data-sort]').forEach((opt) => {
        opt.addEventListener('click', (e) => {
          e.stopPropagation();
          const sortVal = opt.getAttribute('data-sort');
          this.sortBy = sortVal;

          if (sortSelect) sortSelect.value = sortVal;
          if (customSortLabel) {
            const labels = { newest: 'Newest', name: 'Name', size: 'Size' };
            customSortLabel.textContent = labels[sortVal] || sortVal;
          }

          customSortMenu.querySelectorAll('.custom-select-option').forEach((o) => o.classList.remove('selected'));
          opt.classList.add('selected');

          if (customSort) customSort.classList.remove('active');
          this.applyFilterAndRender();
        });
      });

      document.addEventListener('click', (e) => {
        if (customSort && !customSort.contains(e.target)) {
          customSort.classList.remove('active');
        }
      });
    }

    // View toggle (Grid / List)
    const btnToggleGrid = document.getElementById('btn-view-grid');
    const btnToggleList = document.getElementById('btn-view-list');
    if (btnToggleGrid && btnToggleList) {
      const updateViewButtons = () => {
        btnToggleGrid.classList.toggle('active', this.viewMode === 'grid');
        btnToggleList.classList.toggle('active', this.viewMode === 'list');
      };
      updateViewButtons();

      btnToggleGrid.addEventListener('click', () => {
        this.viewMode = 'grid';
        localStorage.setItem('skydrop-received-view', 'grid');
        updateViewButtons();
        this.applyFilterAndRender();
      });

      btnToggleList.addEventListener('click', () => {
        this.viewMode = 'list';
        localStorage.setItem('skydrop-received-view', 'list');
        updateViewButtons();
        this.applyFilterAndRender();
      });
    }

    // Density toggle buttons
    const densityButtons = document.querySelectorAll('.density-btn');
    densityButtons.forEach((btn) => {
      if (btn.getAttribute('data-density') === this.density) {
        btn.classList.add('active');
      }
      btn.addEventListener('click', () => {
        densityButtons.forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        this.density = btn.getAttribute('data-density');
        localStorage.setItem('skydrop-received-density', this.density);
        this.applyDensity();
      });
    });

    // Multi-select toggle button
    const btnSelectMode = document.getElementById('btn-select-mode');
    if (btnSelectMode) {
      btnSelectMode.addEventListener('click', () => {
        this.toggleSelectMode();
      });
    }

    // Clear history
    const clearBtn = document.getElementById('btn-clear-history');
    if (clearBtn) {
      clearBtn.addEventListener('click', async () => {
        if (confirm('Clear received files history? This will not delete files from disk.')) {
          await window.skydrop.clearHistory();
          this.app.refreshHistory();
        }
      });
    }

    // Multi-select toolbar actions
    const selSelectAll = document.getElementById('sel-select-all');
    const selCancel = document.getElementById('sel-cancel');
    const selSend = document.getElementById('sel-send');
    const selDeleteHistory = document.getElementById('sel-delete-history');
    const selDeleteDisk = document.getElementById('sel-delete-disk');

    if (selSelectAll) {
      selSelectAll.addEventListener('click', () => {
        if (this.selectedIds.size === this.filteredEntries.length) {
          this.selectedIds.clear();
        } else {
          this.filteredEntries.forEach((item) => this.selectedIds.add(item.id));
        }
        this.updateSelectionUI();
      });
    }

    if (selCancel) {
      selCancel.addEventListener('click', () => {
        this.toggleSelectMode(false);
      });
    }

    if (selSend) {
      selSend.addEventListener('click', () => {
        const selectedItems = this.filteredEntries.filter((item) => this.selectedIds.has(item.id));
        this.sendBatch(selectedItems);
      });
    }

    if (selDeleteHistory) {
      selDeleteHistory.addEventListener('click', async () => {
        const count = this.selectedIds.size;
        if (confirm(`Remove ${count} selected item(s) from history?`)) {
          for (const id of this.selectedIds) {
            await window.skydrop.deleteHistoryEntry(id);
          }
          this.toggleSelectMode(false);
          this.app.refreshHistory();
        }
      });
    }

    if (selDeleteDisk) {
      selDeleteDisk.addEventListener('click', async () => {
        const count = this.selectedIds.size;
        if (confirm(`⚠️ Permanently DELETE ${count} selected file(s) from your disk? This cannot be undone.`)) {
          for (const id of this.selectedIds) {
            const item = this.rawEntries.find((e) => e.id === id);
            if (item && item.saved_path) {
              await window.skydrop.deleteFileFromDisk(item.saved_path);
            }
            await window.skydrop.deleteHistoryEntry(id);
          }
          this.toggleSelectMode(false);
          this.app.refreshHistory();
        }
      });
    }
  }

  toggleSelectMode(force) {
    this.isSelectMode = typeof force === 'boolean' ? force : !this.isSelectMode;
    if (!this.isSelectMode) {
      this.selectedIds.clear();
    }
    const toolbar = document.getElementById('selection-toolbar');
    const btnSelectMode = document.getElementById('btn-select-mode');
    if (toolbar) toolbar.classList.toggle('active', this.isSelectMode);
    if (btnSelectMode) btnSelectMode.classList.toggle('active', this.isSelectMode);
    this.updateSelectionUI();
  }

  updateSelectionUI() {
    const countEl = document.getElementById('selection-count');
    if (countEl) countEl.textContent = `${this.selectedIds.size} selected`;

    document.querySelectorAll('.media-tile, .history-row-item').forEach((el) => {
      const id = el.getAttribute('data-id');
      const isSelected = this.selectedIds.has(id);
      el.classList.toggle('selected', isSelected);
      const checkbox = el.querySelector('.tile-checkbox');
      if (checkbox) checkbox.checked = isSelected;
    });
  }

  initContextMenu() {
    // Dismiss context menu
    document.addEventListener('click', (e) => {
      if (this.contextMenu && !this.contextMenu.contains(e.target)) {
        this.closeContextMenu();
      }
    });

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') this.closeContextMenu();
    });

    // Context menu actions
    const ctxOpen = document.getElementById('ctx-open');
    const ctxPreview = document.getElementById('ctx-preview');
    const ctxFolder = document.getElementById('ctx-folder');
    const ctxCopyPath = document.getElementById('ctx-copy-path');
    const ctxSendAgain = document.getElementById('ctx-send-again');
    const ctxDelete = document.getElementById('ctx-delete');
    const ctxDeleteDisk = document.getElementById('ctx-delete-disk');

    if (ctxOpen) {
      ctxOpen.addEventListener('click', () => {
        if (this.activeContextItem && this.activeContextItem.saved_path) {
          this.openFile(this.activeContextItem.saved_path);
        }
        this.closeContextMenu();
      });
    }

    if (ctxPreview) {
      ctxPreview.addEventListener('click', () => {
        if (this.activeContextItem) {
          this.openPreview(this.activeContextItem);
        }
        this.closeContextMenu();
      });
    }

    if (ctxFolder) {
      ctxFolder.addEventListener('click', () => {
        if (this.activeContextItem && this.activeContextItem.saved_path) {
          window.skydrop.showInFolder(this.activeContextItem.saved_path);
        }
        this.closeContextMenu();
      });
    }

    if (ctxCopyPath) {
      ctxCopyPath.addEventListener('click', () => {
        if (this.activeContextItem && this.activeContextItem.saved_path) {
          navigator.clipboard.writeText(this.activeContextItem.saved_path);
        }
        this.closeContextMenu();
      });
    }

    if (ctxSendAgain) {
      ctxSendAgain.addEventListener('click', () => {
        if (this.activeContextItem) {
          this.sendBatch([this.activeContextItem]);
        }
        this.closeContextMenu();
      });
    }

    if (ctxDelete) {
      ctxDelete.addEventListener('click', async () => {
        if (this.activeContextItem) {
          await window.skydrop.deleteHistoryEntry(this.activeContextItem.id);
          this.app.refreshHistory();
        }
        this.closeContextMenu();
      });
    }

    if (ctxDeleteDisk) {
      ctxDeleteDisk.addEventListener('click', async () => {
        if (this.activeContextItem && this.activeContextItem.saved_path) {
          if (confirm(`Permanently delete "${this.activeContextItem.filename}" from disk?`)) {
            await window.skydrop.deleteFileFromDisk(this.activeContextItem.saved_path);
            await window.skydrop.deleteHistoryEntry(this.activeContextItem.id);
            this.app.refreshHistory();
          }
        }
        this.closeContextMenu();
      });
    }
  }

  openContextMenu(e, item) {
    e.preventDefault();
    e.stopPropagation();
    this.activeContextItem = item;

    if (!this.contextMenu) return;
    const ctxPreview = document.getElementById('ctx-preview');
    const pres = window.getFileTypePresentation(item.filename);
    if (ctxPreview) {
      ctxPreview.style.display = pres.isVisual ? 'flex' : 'none';
    }

    this.contextMenu.style.left = `${Math.min(e.clientX, window.innerWidth - 180)}px`;
    this.contextMenu.style.top = `${Math.min(e.clientY, window.innerHeight - 220)}px`;
    this.contextMenu.classList.add('active');
  }

  closeContextMenu() {
    if (this.contextMenu) {
      this.contextMenu.classList.remove('active');
    }
    this.activeContextItem = null;
  }

  initKeyboardNav() {
    this.container.addEventListener('keydown', (e) => {
      const activeEl = document.activeElement;
      if (!activeEl || !activeEl.classList.contains('media-tile')) return;

      const tiles = Array.from(this.container.querySelectorAll('.media-tile'));
      const idx = tiles.indexOf(activeEl);
      if (idx === -1) return;

      const cols = this.computeColumnsCount();

      switch (e.key) {
        case 'ArrowRight':
          e.preventDefault();
          if (idx + 1 < tiles.length) tiles[idx + 1].focus();
          break;
        case 'ArrowLeft':
          e.preventDefault();
          if (idx - 1 >= 0) tiles[idx - 1].focus();
          break;
        case 'ArrowDown':
          e.preventDefault();
          if (idx + cols < tiles.length) tiles[idx + cols].focus();
          break;
        case 'ArrowUp':
          e.preventDefault();
          if (idx - cols >= 0) tiles[idx - cols].focus();
          break;
        case 'Enter':
          e.preventDefault();
          activeEl.click();
          break;
        case ' ':
          e.preventDefault();
          const id = activeEl.getAttribute('data-id');
          const item = this.filteredEntries.find((x) => x.id === id);
          if (item) this.openPreview(item);
          break;
        case 'Delete':
        case 'Backspace':
          e.preventDefault();
          const delId = activeEl.getAttribute('data-id');
          if (delId) {
            window.skydrop.deleteHistoryEntry(delId).then(() => this.app.refreshHistory());
          }
          break;
      }
    });
  }

  computeColumnsCount() {
    const grid = this.container.querySelector('.tiles-grid');
    if (!grid) return 1;
    const gridWidth = grid.clientWidth;
    const densities = { compact: 84, comfortable: 112, large: 148 };
    const minW = densities[this.density] || 112;
    const gap = 10;
    return Math.max(1, Math.floor((gridWidth + gap) / (minW + gap)));
  }

  areEntriesEqual(a, b) {
    if (!a || !b) return a === b;
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      const itemA = a[i];
      const itemB = b[i];
      if (
        itemA.id !== itemB.id ||
        itemA.filename !== itemB.filename ||
        itemA.size !== itemB.size ||
        itemA.status !== itemB.status ||
        itemA.saved_path !== itemB.saved_path ||
        itemA.timestamp !== itemB.timestamp ||
        itemA.error_message !== itemB.error_message
      ) {
        return false;
      }
    }
    return true;
  }

  render(entries) {
    const newInbound = (entries || []).filter((e) => e.direction === 'inbound');

    // Update bottom tab badge
    if (newInbound.length > 0) {
      this.badge.style.display = 'block';
      this.badge.textContent = newInbound.length > 99 ? '99+' : String(newInbound.length);
    } else {
      this.badge.style.display = 'none';
    }

    // Guard: Skip expensive DOM re-renders if the inbound list hasn't changed
    if (this.areEntriesEqual(newInbound, this.rawEntries)) {
      return;
    }

    this.rawEntries = newInbound;
    this.applyFilterAndRender();
  }

  applyFilterAndRender() {
    let items = [...this.rawEntries];

    // Search filter
    if (this.searchQuery) {
      items = items.filter((item) => (item.filename || '').toLowerCase().includes(this.searchQuery));
    }

    // Category filter
    if (this.activeFilter !== 'all') {
      items = items.filter((item) => {
        const pres = window.getFileTypePresentation(item.filename);
        return pres.category === this.activeFilter;
      });
    }

    // Sort order
    items.sort((a, b) => {
      if (this.sortBy === 'name') {
        return (a.filename || '').localeCompare(b.filename || '');
      }
      if (this.sortBy === 'size') {
        return (b.size || 0) - (a.size || 0);
      }
      // default: newest
      return new Date(b.timestamp || 0).getTime() - new Date(a.timestamp || 0).getTime();
    });

    this.filteredEntries = items;
    this.groupAndRender();
  }

  groupAndRender() {
    if (this.filteredEntries.length === 0) {
      this.renderEmptyState();
      return;
    }

    // Group into date buckets: Today, Yesterday, This week, Earlier
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const yesterday = today - 86400000;
    const thisWeek = today - 86400000 * 7;

    const groups = {
      Today: [],
      Yesterday: [],
      'This week': [],
      Earlier: []
    };

    this.filteredEntries.forEach((item) => {
      const ts = new Date(item.timestamp || Date.now()).getTime();
      if (ts >= today) {
        groups.Today.push(item);
      } else if (ts >= yesterday) {
        groups.Yesterday.push(item);
      } else if (ts >= thisWeek) {
        groups['This week'].push(item);
      } else {
        groups.Earlier.push(item);
      }
    });

    let html = '';
    for (const [groupName, groupItems] of Object.entries(groups)) {
      if (groupItems.length === 0) continue;

      html += `
        <div class="received-group">
          <div class="sticky-group-header">
            <span class="group-title">${groupName}</span>
            <span class="group-count">${groupItems.length}</span>
          </div>
          ${this.viewMode === 'grid' ? this.renderGrid(groupItems) : this.renderList(groupItems)}
        </div>
      `;
    }

    // Preserve scroll position across renders
    const prevScrollTop = this.container ? this.container.scrollTop : 0;
    this.container.innerHTML = html;
    this.attachItemEvents();
    if (this.container && prevScrollTop > 0) {
      this.container.scrollTop = prevScrollTop;
    }
  }

  renderGrid(items) {
    let gridHtml = `<div class="tiles-grid" role="grid" aria-label="Received files grid">`;
    items.forEach((item) => {
      const pres = window.getFileTypePresentation(item.filename);
      const isSelected = this.selectedIds.has(item.id);
      const isFailed = item.status === 'failed';
      const isPending = item.status === 'transferring';
      const cachedThumb = item.saved_path ? this.renderedThumbs.get(item.saved_path) : null;

      gridHtml += `
        <div class="media-tile ${isSelected ? 'selected' : ''}"
             id="tile-${item.id}"
             data-id="${item.id}"
             data-filepath="${escapePath(item.saved_path)}"
             data-visual="${pres.isVisual}"
             data-video="${pres.isVideo}"
             tabindex="0"
             role="gridcell"
             aria-label="${escapeHtml(item.filename)}, ${formatBytes(item.size)}, ${pres.label}"
             draggable="true">

          <!-- Checkbox for multi-select -->
          <div class="tile-checkbox-wrapper">
            <input type="checkbox" class="tile-checkbox" ${isSelected ? 'checked' : ''} tabindex="-1">
          </div>

          <!-- Type Badge -->
          <span class="tile-badge" style="background-color: ${pres.bgTint}; color: ${pres.accent};">
            ${pres.label}
          </span>

          <!-- Thumbnail Box / Skeleton Placeholder -->
          <div class="tile-thumb-box" style="background-color: ${pres.bgTint};">
            ${
              cachedThumb
                ? `<img class="tile-thumb-img" src="${cachedThumb}" alt="thumbnail" loading="lazy" draggable="false">`
                : `<div class="tile-placeholder-icon" style="color: ${pres.accent};">${pres.svg}</div>`
            }
            ${pres.isVideo ? '<div class="video-play-badge"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg></div>' : ''}
          </div>

          <!-- Status Overlays -->
          ${isFailed ? `<div class="tile-status-overlay tile-status-failed" title="${escapeHtml(item.error_message || 'Transfer failed')}"><span class="material-symbols-rounded" style="font-size: 13px;">error</span> Failed</div>` : ''}
          ${isPending ? `<div class="tile-status-overlay tile-status-pending"><div class="mini-spinner"></div></div>` : ''}

          <!-- Tile Info Footer -->
          <div class="tile-info">
            <div class="tile-name" title="${escapeHtml(item.filename)}">${escapeHtml(item.filename)}</div>
            <div class="tile-meta">${formatBytes(item.size)}</div>
          </div>
        </div>
      `;
    });
    gridHtml += `</div>`;
    return gridHtml;
  }

  renderList(items) {
    let listHtml = `<div class="history-list-view" role="list">`;
    items.forEach((item) => {
      const pres = window.getFileTypePresentation(item.filename);
      const isSelected = this.selectedIds.has(item.id);
      const isFailed = item.status === 'failed';
      const timeStr = item.timestamp
        ? new Date(item.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
        : '';
      const cachedThumb = item.saved_path ? this.renderedThumbs.get(item.saved_path) : null;

      listHtml += `
        <div class="history-item history-row-item ${isSelected ? 'selected' : ''}"
             id="row-${item.id}"
             data-id="${item.id}"
             data-filepath="${escapePath(item.saved_path)}"
             data-visual="${pres.isVisual}"
             data-video="${pres.isVideo}"
             tabindex="0"
             role="listitem"
             draggable="true">
          <div class="history-left">
            <div class="tile-checkbox-wrapper" style="position: static; margin-right: 8px;">
              <input type="checkbox" class="tile-checkbox" ${isSelected ? 'checked' : ''} tabindex="-1">
            </div>
            <div class="media-thumb-box" style="background-color: ${pres.bgTint};">
              ${
                cachedThumb
                  ? `<img class="media-thumb-img" src="${cachedThumb}" alt="thumbnail" loading="lazy" draggable="false">`
                  : `<span class="tile-placeholder-icon" style="color: ${pres.accent};">${pres.svg}</span>`
              }
              ${pres.isVideo ? '<div class="video-play-badge"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg></div>' : ''}
            </div>
            <div class="history-details">
              <div class="history-name" title="${escapeHtml(item.filename)}">${escapeHtml(item.filename)}</div>
              <div class="history-meta">
                ${formatBytes(item.size)} • ${timeStr} ${item.peer_name ? `• from ${escapeHtml(item.peer_name)}` : ''}
                ${isFailed ? `<span style="color: var(--color-danger); font-weight: 600;">(Failed)</span>` : ''}
              </div>
            </div>
          </div>
          <div class="history-actions">
            ${
              !isFailed && item.saved_path
                ? `
              <button class="btn-tiny" onclick="event.stopPropagation(); window.receivedView.openFile('${escapePath(item.saved_path)}')">Open</button>
              <button class="btn-tiny" onclick="event.stopPropagation(); window.skydrop.showInFolder('${escapePath(item.saved_path)}')">Folder</button>
            `
                : ''
            }
          </div>
        </div>
      `;
    });
    listHtml += `</div>`;
    return listHtml;
  }

  attachItemEvents() {
    // Disconnect stale observers before re-observing new elements
    if (this.observer) {
      this.observer.disconnect();
    }

    const tiles = this.container.querySelectorAll('.media-tile, .history-row-item');
    tiles.forEach((tile) => {
      const id = tile.getAttribute('data-id');
      const item = this.filteredEntries.find((x) => x.id === id);
      if (!item) return;

      // Only observe for thumbnail loading if not already cached and rendered
      const isVisual = tile.getAttribute('data-visual') === 'true';
      const filePath = tile.getAttribute('data-filepath');
      if (isVisual && filePath) {
        if (!this.renderedThumbs.has(filePath)) {
          this.observer.observe(tile);
        }
      }

      // Click behavior
      tile.addEventListener('click', (e) => {
        if (this.isSelectMode || e.ctrlKey || e.metaKey || e.shiftKey) {
          e.preventDefault();
          this.toggleItemSelection(item, e.shiftKey);
          return;
        }

        // Open quick preview or default app
        const pres = window.getFileTypePresentation(item.filename);
        if (pres.isVisual && item.saved_path && item.status !== 'failed') {
          this.openPreview(item);
        } else if (item.saved_path && item.status !== 'failed') {
          this.openFile(item.saved_path);
        }
      });

      // Right-click context menu
      tile.addEventListener('contextmenu', (e) => {
        this.openContextMenu(e, item);
      });

      // Drag-out support
      tile.addEventListener('dragstart', (e) => {
        if (item.saved_path) {
          e.preventDefault();
          window.skydrop.startDrag(item.saved_path);
        }
      });
    });
  }

  async loadThumbnail(tile, filePath, isVideo) {
    if (this.renderedThumbs.has(filePath)) {
      this.applyThumbnailToTile(tile, this.renderedThumbs.get(filePath), isVideo);
      return;
    }

    try {
      const res = await window.skydrop.getFileThumbnail(filePath);
      if (res && res.missing) {
        tile.classList.add('file-missing');
        const info = tile.querySelector('.tile-meta');
        if (info) info.innerHTML = '<span style="color: var(--color-danger); font-weight: 600;">File missing</span>';
        return;
      }
      if (res) {
        this.renderedThumbs.set(filePath, res);
        this.applyThumbnailToTile(tile, res, isVideo);
      }
    } catch (_) {}
  }

  applyThumbnailToTile(tile, dataUrl, isVideo) {
    const thumbBox = tile.querySelector('.tile-thumb-box, .media-thumb-box');
    if (!thumbBox) return;

    thumbBox.innerHTML = `
      <img class="tile-thumb-img" src="${dataUrl}" alt="thumbnail" loading="lazy" draggable="false">
      ${isVideo ? '<div class="video-play-badge"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg></div>' : ''}
    `;
  }

  toggleItemSelection(item, isRange) {
    if (isRange && this.lastSelectedId) {
      const idx1 = this.filteredEntries.findIndex((x) => x.id === this.lastSelectedId);
      const idx2 = this.filteredEntries.findIndex((x) => x.id === item.id);
      if (idx1 !== -1 && idx2 !== -1) {
        const [start, end] = idx1 < idx2 ? [idx1, idx2] : [idx2, idx1];
        for (let i = start; i <= end; i++) {
          this.selectedIds.add(this.filteredEntries[i].id);
        }
      }
    } else {
      if (this.selectedIds.has(item.id)) {
        this.selectedIds.delete(item.id);
      } else {
        this.selectedIds.add(item.id);
      }
      this.lastSelectedId = item.id;
    }

    if (this.selectedIds.size > 0 && !this.isSelectMode) {
      this.toggleSelectMode(true);
    } else {
      this.updateSelectionUI();
    }
  }

  openPreview(item) {
    if (!item) return;
    const p = typeof window.getFileTypePresentation === 'function'
      ? window.getFileTypePresentation(item.filename)
      : {};

    // Documents (PDFs, Text, Word, Excel, PPT, Code, Archives, APKs) -> Open in system default app (Browser, Text Editor, Office, etc.)
    if (!p.isVisual && !p.isAudio) {
      if (item.saved_path) {
        this.openFile(item.saved_path);
      }
      return;
    }

    // Media Files (Images, Videos, Audio) -> Open in SkyDrop In-App Lightbox
    const mediaItems = this.filteredEntries.filter((x) => {
      const pres = window.getFileTypePresentation(x.filename);
      return (pres.isVisual || pres.isAudio) && x.saved_path;
    });
    const startIndex = mediaItems.findIndex((x) => x.id === item.id);
    if (startIndex !== -1) {
      this.lightbox.open(mediaItems, startIndex);
    } else if (item.saved_path) {
      this.openFile(item.saved_path);
    }
  }

  async openFile(path) {
    if (!path) return;
    const res = await window.skydrop.openPath(path);
    if (!res.ok) {
      alert(`Could not open file: ${res.error || 'File not found'}`);
    }
  }

  sendBatch(items) {
    if (!items || items.length === 0) return;
    this.app.switchTab('screen-send');
    if (window.sendView) {
      window.sendView.addFiles(
        items
          .filter((item) => item.saved_path)
          .map((item) => ({
            path: item.saved_path,
            name: item.filename,
            size: item.size
          }))
      );
    }
  }

  renderEmptyState() {
    const hasFilter = this.searchQuery || this.activeFilter !== 'all';
    this.container.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">${hasFilter ? '🔍' : '📥'}</div>
        <div style="font-size: 14px; font-weight: 700; color: var(--text-main);">
          ${hasFilter ? 'No matching received files' : 'Nothing received yet'}
        </div>
        <div style="font-size: 11px; margin-top: 6px; color: var(--text-muted); line-height: 1.5; max-width: 260px;">
          ${hasFilter ? 'Try clearing your search query or choosing another filter category.' : 'Files dropped from your phone or nearby devices will appear here with live thumbnails and quick preview.'}
        </div>
      </div>
    `;
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function escapePath(p) {
  if (!p) return '';
  return p.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  if (!bytes) return '';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

window.ReceivedView = ReceivedView;
window.formatBytes = formatBytes;
