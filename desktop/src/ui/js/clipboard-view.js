/**
 * clipboard-view.js
 * Clipboard view controller for SkyDrop desktop UI.
 * Depends on clipboard-view-render.js (must be loaded first).
 *
 * Exports window.ClipboardView  { load() }
 * No innerHTML with user content – XSS prevention throughout.
 */
class ClipboardView {
  constructor(app) {
    this.app = app;

    // DOM refs – resolved on first load() call when the view is visible
    this._listEl = null;
    this._emptyEl = null;
    this._addInput = null;
    this._addBtn = null;
    this._clearBtn = null;
    this._syncBadge = null;
    this._syncToggle = null;
    this._captureToggle = null;
    this._captureWarning = null;

    this._pollTimer = null;
    this._eventsUnsubscribe = null;
    this._loaded = false;
  }

  // ── Lazy DOM resolution ───────────────────────────────────────────────────

  _resolveEls() {
    this._listEl        = document.getElementById('clipboard-items-list');
    this._emptyEl       = document.getElementById('clipboard-empty-state');
    this._addInput      = document.getElementById('clipboard-add-input');
    this._addBtn        = document.getElementById('clipboard-add-btn');
    this._clearBtn      = document.getElementById('clipboard-clear-btn');
    this._syncBadge     = document.getElementById('clipboard-sync-status');
    this._syncToggle    = document.getElementById('clipboard-sync-toggle');
    this._captureToggle = document.getElementById('clipboard-auto-capture-toggle');
    this._captureWarning= document.getElementById('clipboard-capture-warning');
  }

  // ── Public API ────────────────────────────────────────────────────────────

  async load() {
    this._resolveEls();

    if (!this._loaded) {
      this._bindEvents();
      this._subscribeToUpdates();
      this._loaded = true;
    }

    await Promise.all([this._fetchAndRender(), this._loadSettings()]);
    this._startPolling();
  }

  // ── Data fetching ─────────────────────────────────────────────────────────

  async _fetchAndRender() {
    try {
      const res = await window.skydrop.clipboardList();
      const items = res && res.ok && res.data ? (Array.isArray(res.data.items) ? res.data.items : (Array.isArray(res.data) ? res.data : null)) : null;
      if (items !== null) {
        this._renderItems(items);
        window.ClipboardViewRender.updateSyncBadge(this._syncBadge, 'synced');
      } else {
        window.ClipboardViewRender.updateSyncBadge(this._syncBadge, 'error');
      }
    } catch (_) {
      window.ClipboardViewRender.updateSyncBadge(this._syncBadge, 'offline');
    }
  }

  async _loadSettings() {
    try {
      const res = await window.skydrop.clipboardGetSettings();
      if (res && res.ok && res.data) {
        const s = res.data;
        if (this._syncToggle) {
          this._syncToggle.checked = s.sync_enabled !== false;
        }
        if (this._captureToggle) {
          this._captureToggle.checked = Boolean(s.auto_capture);
          this._toggleCaptureWarning(Boolean(s.auto_capture));
        }
      }
    } catch (_) {
      // Settings endpoint may not exist yet; fail silently
    }
  }

  // ── Rendering ─────────────────────────────────────────────────────────────

  _renderItems(items) {
    if (!this._listEl) return;
    window.ClipboardViewRender.renderItemList(
      this._listEl,
      this._emptyEl,
      items,
      {
        onCopy:   (item, btn) => this._handleCopy(item, btn),
        onPin:    (item)      => this._handlePin(item),
        onDelete: (item, card) => this._handleDelete(item, card)
      }
    );
  }

  _prependCard(item) {
    if (!this._listEl) return;

    // If currently in empty state, switch to list view
    if (this._emptyEl) this._emptyEl.style.display = 'none';
    this._listEl.style.display = 'flex';

    const card = window.ClipboardViewRender.buildItemCard(item, {
      onCopy:   (i, btn) => this._handleCopy(i, btn),
      onPin:    (i)      => this._handlePin(i),
      onDelete: (i, c)   => this._handleDelete(i, c)
    });

    this._listEl.insertBefore(card, this._listEl.firstChild);
  }

  // ── Event binding ─────────────────────────────────────────────────────────

  _bindEvents() {
    if (this._addBtn) {
      this._addBtn.addEventListener('click', () => this._handleAdd());
    }

    if (this._addInput) {
      this._addInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          this._handleAdd();
        }
      });
    }

    if (this._clearBtn) {
      this._clearBtn.addEventListener('click', () => this._handleClear());
    }

    if (this._syncToggle) {
      this._syncToggle.addEventListener('change', () => this._handleSettingChange());
    }

    if (this._captureToggle) {
      this._captureToggle.addEventListener('change', () => {
        this._toggleCaptureWarning(this._captureToggle.checked);
        this._handleSettingChange();
      });
    }
  }

  _subscribeToUpdates() {
    if (!window.skydrop || typeof window.skydrop.onClipboardUpdate !== 'function') return;
    this._eventsUnsubscribe = window.skydrop.onClipboardUpdate(() => {
      this._fetchAndRender();
    });
  }

  // ── Handlers ──────────────────────────────────────────────────────────────

  async _handleAdd() {
    if (!this._addInput) return;
    const text = this._addInput.value;
    const err = window.ClipboardViewRender.validateAddText(text);

    if (err) {
      this._showInputError(err);
      return;
    }

    this._addBtn.disabled = true;
    try {
      const res = await window.skydrop.clipboardAdd(text);
      if (res && res.ok) {
        this._addInput.value = '';
        // Prepend newly created item if data is returned
        const newItem = (res.data && res.data.item) || res.data;
        if (newItem && newItem.id) {
          this._prependCard(newItem);
        } else {
          await this._fetchAndRender();
        }
      } else {
        this._showInputError((res && res.error) || 'Failed to add item.');
      }
    } catch (e) {
      this._showInputError('Failed to add item: ' + e.message);
    } finally {
      this._addBtn.disabled = false;
    }
  }

  async _handleCopy(item, btn) {
    try {
      await navigator.clipboard.writeText(item.text);
      window.ClipboardViewRender.flashButtonLabel(btn, '✓ Copied!');
    } catch (_) {
      window.ClipboardViewRender.flashButtonLabel(btn, '✗ Failed');
    }
  }

  async _handlePin(item) {
    try {
      const res = await window.skydrop.clipboardPin(item.id, !item.pinned);
      if (res && res.ok) {
        await this._fetchAndRender();
      }
    } catch (_) {}
  }

  async _handleDelete(item, card) {
    try {
      const res = await window.skydrop.clipboardDelete(item.id);
      if (res && res.ok) {
        window.ClipboardViewRender.removeCardAnimated(card, () => {
          if (this._listEl && this._listEl.children.length === 0) {
            this._listEl.style.display = 'none';
            if (this._emptyEl) this._emptyEl.style.display = 'flex';
          }
        });
      }
    } catch (_) {}
  }

  async _handleClear() {
    const confirmed = window.confirm('Delete all unpinned clipboard items?');
    if (!confirmed) return;

    try {
      const res = await window.skydrop.clipboardClear(false);
      if (res && res.ok) {
        await this._fetchAndRender();
      }
    } catch (_) {}
  }

  async _handleSettingChange() {
    try {
      await window.skydrop.clipboardSetSettings({
        sync_enabled: this._syncToggle ? this._syncToggle.checked : true,
        auto_capture: this._captureToggle ? this._captureToggle.checked : false
      });
    } catch (_) {}
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  _toggleCaptureWarning(show) {
    if (!this._captureWarning) return;
    this._captureWarning.style.display = show ? 'block' : 'none';
  }

  _showInputError(message) {
    if (!this._addInput) return;
    this._addInput.setCustomValidity(message);
    this._addInput.reportValidity();
    // Reset validity after user starts typing again
    this._addInput.addEventListener('input', () => {
      this._addInput.setCustomValidity('');
    }, { once: true });
  }

  _startPolling() {
    this._stopPolling();
    this._pollTimer = setInterval(() => {
      if (document.hidden) return;
      this._fetchAndRender();
    }, 10000);
  }

  _stopPolling() {
    if (this._pollTimer) {
      clearInterval(this._pollTimer);
      this._pollTimer = null;
    }
  }
}

window.ClipboardView = ClipboardView;
