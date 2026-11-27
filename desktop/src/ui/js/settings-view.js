/**
 * Settings view: manages destination directory, device name, auto-accept, autostart,
 * daemon statistics, build-driven version, and GitHub-based auto-updater.
 */
class SettingsView {
  constructor(app) {
    this.app = app;
    this.storageDirEl = document.getElementById('settings-storage-dir');
    this.deviceNameInput = document.getElementById('settings-device-name');
    this.requirePairCheckbox = document.getElementById('settings-require-pair');
    this.autostartCheckbox = document.getElementById('settings-autostart');
    this.alertEl = document.getElementById('settings-alert');

    // Branding and Daemon Status elements
    this.appVersionDisplay = document.getElementById('app-version-display');
    this.statPort = document.getElementById('stat-port');
    this.statDaemon = document.getElementById('stat-daemon');
    this.statVersion = document.getElementById('stat-version');
    this.statFingerprint = document.getElementById('stat-fingerprint');

    // Software Updates elements
    this.updateStatusBadge = document.getElementById('update-status-badge');
    this.updateCurrentVersion = document.getElementById('update-current-version');
    this.updateLastChecked = document.getElementById('update-last-checked');
    this.autoUpdateCheckbox = document.getElementById('settings-auto-update');
    this.btnCheckUpdates = document.getElementById('btn-check-updates');
    this.btnInstallUpdate = document.getElementById('btn-install-update');
    this.btnRestartApp = document.getElementById('btn-restart-app');
    this.updateProgressContainer = document.getElementById('update-progress-container');
    this.updateProgressBar = document.getElementById('update-progress-bar');
    this.updateProgressText = document.getElementById('update-progress-text');
    this.updateProgressPercent = document.getElementById('update-progress-percent');
    this.updateMessage = document.getElementById('update-message');

    this.pendingUpdate = null;
    this.initEvents();
  }

  showAlert(message, type = 'error') {
    if (!this.alertEl) return;
    this.alertEl.textContent = message;
    this.alertEl.className = `settings-alert ${type}`;
    this.alertEl.style.display = 'block';

    if (this._alertTimeout) clearTimeout(this._alertTimeout);
    this._alertTimeout = setTimeout(() => {
      if (this.alertEl) this.alertEl.style.display = 'none';
    }, 6000);
  }

  formatTimestamp(isoString) {
    if (!isoString) return 'Never';
    try {
      const date = new Date(isoString);
      if (isNaN(date.getTime())) return 'Never';
      const now = new Date();
      const diffMs = now.getTime() - date.getTime();
      const diffMins = Math.floor(diffMs / 60000);

      if (diffMins < 1) return 'Just now';
      if (diffMins < 60) return `${diffMins}m ago`;
      const diffHours = Math.floor(diffMins / 60);
      if (diffHours < 24) return `${diffHours}h ago`;
      return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    } catch (_) {
      return 'Never';
    }
  }

  initEvents() {
    // Browse destination folder
    document.getElementById('btn-change-folder').addEventListener('click', async () => {
      const selected = await window.skydrop.selectFolder();
      if (selected) {
        const res = await window.skydrop.updateSettings({ storage_dir: selected });
        if (res.ok && res.data) {
          this.storageDirEl.textContent = res.data.storage_dir;
        }
      }
    });

    // Save device name
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

    // Auto-accept toggle
    this.requirePairCheckbox.addEventListener('change', async () => {
      await window.skydrop.updateSettings({
        require_pairing: this.requirePairCheckbox.checked
      });
    });

    // Start on Login toggle with verification and failure reversion
    this.autostartCheckbox.addEventListener('change', async () => {
      const desiredState = this.autostartCheckbox.checked;
      try {
        const res = await window.skydrop.setAutostart(desiredState);
        if (!res || !res.ok) {
          // Revert toggle on failure
          this.autostartCheckbox.checked = !desiredState;
          const msg = res && res.error ? res.error : 'Failed to update OS autostart configuration.';
          this.showAlert(msg, 'error');
        } else {
          this.autostartCheckbox.checked = Boolean(res.enabled);
        }
      } catch (err) {
        this.autostartCheckbox.checked = !desiredState;
        this.showAlert(`Failed to update start on login: ${err.message}`, 'error');
      }
    });

    // Clear thumbnail cache button
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

    // Auto-update toggle
    if (this.autoUpdateCheckbox) {
      this.autoUpdateCheckbox.addEventListener('change', async () => {
        if (window.skydrop.setUpdateSettings) {
          await window.skydrop.setUpdateSettings({ autoCheck: this.autoUpdateCheckbox.checked });
        }
      });
    }

    // Check for updates button
    if (this.btnCheckUpdates) {
      this.btnCheckUpdates.addEventListener('click', async () => {
        await this.performUpdateCheck();
      });
    }

    // Download & Install button
    if (this.btnInstallUpdate) {
      this.btnInstallUpdate.addEventListener('click', async () => {
        await this.performDownloadAndInstall();
      });
    }

    // Restart app button
    if (this.btnRestartApp) {
      this.btnRestartApp.addEventListener('click', () => {
        if (window.skydrop.restartApp) {
          window.skydrop.restartApp();
        }
      });
    }

    // IPC listener for download progress
    if (window.skydrop.onUpdateProgress) {
      window.skydrop.onUpdateProgress((prog) => {
        this.handleProgressUpdate(prog);
      });
    }

    // IPC listener for background update available notifications
    if (window.skydrop.onUpdateAvailable) {
      window.skydrop.onUpdateAvailable((update) => {
        this.displayUpdateAvailable(update);
      });
    }
  }

  async performUpdateCheck() {
    if (!this.btnCheckUpdates) return;
    this.btnCheckUpdates.disabled = true;
    this.btnCheckUpdates.textContent = 'Checking...';
    if (this.updateStatusBadge) {
      this.updateStatusBadge.textContent = 'Checking...';
      this.updateStatusBadge.style.color = 'var(--color-primary)';
    }
    if (this.updateMessage) this.updateMessage.style.display = 'none';

    try {
      const res = await window.skydrop.checkForUpdates();
      const lastCheck = await window.skydrop.getUpdateSettings();
      if (this.updateLastChecked && lastCheck) {
        this.updateLastChecked.textContent = this.formatTimestamp(lastCheck.lastCheckTime);
      }

      if (!res || !res.ok) {
        if (this.updateStatusBadge) {
          this.updateStatusBadge.textContent = 'Error';
          this.updateStatusBadge.style.color = 'var(--color-danger)';
        }
        if (this.updateMessage) {
          this.updateMessage.style.display = 'block';
          this.updateMessage.style.background = 'var(--color-danger-bg)';
          this.updateMessage.style.color = 'var(--color-danger)';
          this.updateMessage.textContent = res && res.error ? res.error : 'Failed to connect to update server.';
        }
        return;
      }

      if (res.updateAvailable) {
        this.displayUpdateAvailable(res);
      } else {
        if (this.updateStatusBadge) {
          this.updateStatusBadge.textContent = 'Up to date';
          this.updateStatusBadge.style.color = 'var(--color-success)';
        }
        if (this.btnInstallUpdate) this.btnInstallUpdate.style.display = 'none';
        if (this.btnRestartApp) this.btnRestartApp.style.display = 'none';
        if (this.updateProgressContainer) this.updateProgressContainer.style.display = 'none';
        if (this.updateMessage) {
          this.updateMessage.style.display = 'block';
          this.updateMessage.style.background = 'var(--color-success-bg)';
          this.updateMessage.style.color = 'var(--color-success)';
          this.updateMessage.textContent = `You are running the latest version of SkyDrop (${res.currentVersion}).`;
        }
      }
    } catch (err) {
      if (this.updateStatusBadge) {
        this.updateStatusBadge.textContent = 'Error';
        this.updateStatusBadge.style.color = 'var(--color-danger)';
      }
      if (this.updateMessage) {
        this.updateMessage.style.display = 'block';
        this.updateMessage.style.background = 'var(--color-danger-bg)';
        this.updateMessage.style.color = 'var(--color-danger)';
        this.updateMessage.textContent = `Check failed: ${err.message}`;
      }
    } finally {
      this.btnCheckUpdates.disabled = false;
      this.btnCheckUpdates.textContent = 'Check for Updates';
    }
  }

  displayUpdateAvailable(update) {
    this.pendingUpdate = update;
    if (update && update.releaseNotes) {
      try {
        localStorage.setItem('skydrop-pending-notes', update.releaseNotes);
      } catch (_) {}
    }
    if (this.updateStatusBadge) {
      this.updateStatusBadge.textContent = `v${update.latestVersion} Available`;
      this.updateStatusBadge.style.color = 'var(--color-primary)';
    }

    if (this.btnInstallUpdate) {
      this.btnInstallUpdate.style.display = 'block';
      this.btnInstallUpdate.disabled = false;
      this.btnInstallUpdate.textContent = 'Download & Install';
    }

    if (this.updateMessage) {
      this.updateMessage.style.display = 'block';
      this.updateMessage.style.background = 'var(--bg-surface-elevated)';
      this.updateMessage.style.color = 'var(--text-secondary)';
      const notes = update.releaseNotes ? update.releaseNotes.substring(0, 200) : 'New release available.';
      this.updateMessage.innerHTML = `<strong>Release Notes (${update.latestVersion}):</strong><br>${notes}`;
    }
  }

  handleProgressUpdate(prog) {
    if (!this.updateProgressContainer) return;
    this.updateProgressContainer.style.display = 'block';
    if (this.updateProgressBar) {
      this.updateProgressBar.style.width = `${prog.percent}%`;
    }
    if (this.updateProgressPercent) {
      this.updateProgressPercent.textContent = `${prog.percent}%`;
    }
    if (this.updateProgressText && prog.total > 0) {
      const mbTrans = (prog.transferred / (1024 * 1024)).toFixed(1);
      const mbTotal = (prog.total / (1024 * 1024)).toFixed(1);
      this.updateProgressText.textContent = `Downloading: ${mbTrans} MB / ${mbTotal} MB`;
    }
  }

  async performDownloadAndInstall() {
    if (!this.btnInstallUpdate) return;
    this.btnInstallUpdate.disabled = true;
    this.btnInstallUpdate.textContent = 'Downloading...';
    if (this.btnCheckUpdates) this.btnCheckUpdates.disabled = true;
    if (this.updateStatusBadge) {
      this.updateStatusBadge.textContent = 'Downloading...';
    }
    if (this.updateProgressContainer) {
      this.updateProgressContainer.style.display = 'block';
      if (this.updateProgressBar) this.updateProgressBar.style.width = '0%';
    }

    try {
      const res = await window.skydrop.downloadAndInstallUpdate();
      if (!res || !res.ok) {
        if (this.updateStatusBadge) {
          this.updateStatusBadge.textContent = 'Install Failed';
          this.updateStatusBadge.style.color = 'var(--color-danger)';
        }
        if (this.updateMessage) {
          this.updateMessage.style.display = 'block';
          this.updateMessage.style.background = 'var(--color-danger-bg)';
          this.updateMessage.style.color = 'var(--color-danger)';
          this.updateMessage.textContent = res && res.error ? res.error : 'Failed to install update.';
        }
        this.btnInstallUpdate.disabled = false;
        this.btnInstallUpdate.textContent = 'Retry Install';
        return;
      }

      // Download and install succeeded
      if (this.updateStatusBadge) {
        this.updateStatusBadge.textContent = 'Ready to Restart';
        this.updateStatusBadge.style.color = 'var(--color-success)';
      }
      if (this.btnInstallUpdate) this.btnInstallUpdate.style.display = 'none';
      if (this.btnRestartApp) this.btnRestartApp.style.display = 'block';
      if (this.updateProgressContainer) this.updateProgressContainer.style.display = 'none';
      if (this.updateMessage) {
        this.updateMessage.style.display = 'block';
        this.updateMessage.style.background = 'var(--color-success-bg)';
        this.updateMessage.style.color = 'var(--color-success)';
        this.updateMessage.textContent = 'Update downloaded and staged successfully. Restart SkyDrop to complete the update.';
      }
    } catch (err) {
      if (this.updateStatusBadge) {
        this.updateStatusBadge.textContent = 'Error';
        this.updateStatusBadge.style.color = 'var(--color-danger)';
      }
      if (this.updateMessage) {
        this.updateMessage.style.display = 'block';
        this.updateMessage.style.background = 'var(--color-danger-bg)';
        this.updateMessage.style.color = 'var(--color-danger)';
        this.updateMessage.textContent = `Update error: ${err.message}`;
      }
      this.btnInstallUpdate.disabled = false;
      this.btnInstallUpdate.textContent = 'Retry Install';
    } finally {
      if (this.btnCheckUpdates) this.btnCheckUpdates.disabled = false;
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
      // 1. Load app version dynamically from build
      let appVersion = '1.0.0';
      if (window.skydrop.getAppVersion) {
        appVersion = await window.skydrop.getAppVersion();
      }
      if (this.appVersionDisplay) {
        this.appVersionDisplay.textContent = `v${appVersion}`;
      }
      if (this.updateCurrentVersion) {
        this.updateCurrentVersion.textContent = `v${appVersion}`;
      }

      // 2. Load daemon settings & version
      const res = await window.skydrop.getSettings();
      if (res.ok && res.data) {
        const s = res.data;
        this.storageDirEl.textContent = s.storage_dir || 'Downloads/SkyDrop';
        this.deviceNameInput.value = s.device_name || '';
        this.requirePairCheckbox.checked = s.require_pairing !== false;
        this.statPort.textContent = `${s.port || 8443} (${s.enable_tls ? 'HTTPS' : 'HTTP'})`;
        this.statFingerprint.textContent = s.fingerprint ? s.fingerprint.substring(0, 32) + '...' : 'None';
        if (this.statVersion) {
          const daemonVer = s.version || appVersion;
          this.statVersion.textContent = `v${daemonVer}`;
        }
      }

      // 3. Load OS autostart state directly from system
      const autostart = await window.skydrop.getAutostart();
      if (autostart && typeof autostart.enabled === 'boolean') {
        this.autostartCheckbox.checked = autostart.enabled;
      } else {
        this.autostartCheckbox.checked = Boolean(autostart);
      }

      // 4. Load update preferences
      if (window.skydrop.getUpdateSettings) {
        const upd = await window.skydrop.getUpdateSettings();
        if (upd) {
          if (this.autoUpdateCheckbox) {
            this.autoUpdateCheckbox.checked = upd.autoCheck !== false;
          }
          if (this.updateLastChecked) {
            this.updateLastChecked.textContent = this.formatTimestamp(upd.lastCheckTime);
          }
        }
      }

      await this.loadCacheStats();
    } catch (err) {
      console.warn('Failed to load settings:', err);
    }
  }
}

window.SettingsView = SettingsView;
