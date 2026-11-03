/**
 * Settings view: manages destination directory, device name, auto-accept, autostart, and daemon stats.
 */
class SettingsView {
  constructor(app) {
    this.app = app;
    this.storageDirEl = document.getElementById('settings-storage-dir');
    this.deviceNameInput = document.getElementById('settings-device-name');
    this.requirePairCheckbox = document.getElementById('settings-require-pair');
    this.autostartCheckbox = document.getElementById('settings-autostart');

    this.statPort = document.getElementById('stat-port');
    this.statDaemon = document.getElementById('stat-daemon');
    this.statFingerprint = document.getElementById('stat-fingerprint');

    this.initEvents();
  }

  initEvents() {
    document.getElementById('btn-change-folder').addEventListener('click', async () => {
      const selected = await window.skydrop.selectFolder();
      if (selected) {
        const res = await window.skydrop.updateSettings({ storage_dir: selected });
        if (res.ok && res.data) {
          this.storageDirEl.textContent = res.data.storage_dir;
        }
      }
    });

    document.getElementById('btn-save-device-name').addEventListener('click', async () => {
      const name = this.deviceNameInput.value.trim();
      if (name) {
        const res = await window.skydrop.updateSettings({ device_name: name });
        if (res.ok) {
          const btn = document.getElementById('btn-save-device-name');
          btn.textContent = 'Saved!';
          setTimeout(() => (btn.textContent = 'Save'), 1500);
          this.app.refreshPeers();
        }
      }
    });

    this.requirePairCheckbox.addEventListener('change', async () => {
      await window.skydrop.updateSettings({
        require_pairing: this.requirePairCheckbox.checked
      });
    });

    this.autostartCheckbox.addEventListener('change', async () => {
      await window.skydrop.setAutostart(this.autostartCheckbox.checked);
    });

    const clearCacheBtn = document.getElementById('btn-clear-thumb-cache');
    if (clearCacheBtn) {
      clearCacheBtn.addEventListener('click', async () => {
        clearCacheBtn.textContent = 'Clearing...';
        await window.skydrop.clearThumbnailCache();
        await this.loadCacheStats();
        clearCacheBtn.textContent = 'Cleared!';
        setTimeout(() => (clearCacheBtn.textContent = 'Clear Cache'), 1500);
      });
    }
  }

  async loadCacheStats() {
    const statsEl = document.getElementById('thumb-cache-stats');
    if (statsEl) {
      try {
        const stats = await window.skydrop.getThumbnailCacheStats();
        const mb = (stats.totalSizeBytes / (1024 * 1024)).toFixed(1);
        statsEl.textContent = `${stats.count} files (${mb} MB)`;
      } catch (_) {
        statsEl.textContent = '0 files (0 MB)';
      }
    }
  }

  async load() {
    try {
      const res = await window.skydrop.getSettings();
      if (res.ok && res.data) {
        const s = res.data;
        this.storageDirEl.textContent = s.storage_dir || 'Downloads/SkyDrop';
        this.deviceNameInput.value = s.device_name || '';
        this.requirePairCheckbox.checked = s.require_pairing !== false;
        this.statPort.textContent = `${s.port || 8443} (${s.enable_tls ? 'HTTPS' : 'HTTP'})`;
        this.statFingerprint.textContent = s.fingerprint ? s.fingerprint.substring(0, 32) + '...' : 'None';
      }

      const autostart = await window.skydrop.getAutostart();
      this.autostartCheckbox.checked = Boolean(autostart);

      await this.loadCacheStats();
    } catch (err) {
      console.warn('Failed to load settings:', err);
    }
  }
}

window.SettingsView = SettingsView;
