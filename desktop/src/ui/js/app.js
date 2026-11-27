/**
 * SkyDrop Desktop Main UI Controller
 */
class SkyDropApp {
  constructor() {
    this.devicesView = null;
    this.sendView = null;
    this.receivedView = null;
    this.settingsView = null;
    this.activeScreen = 'screen-devices';
    this.pollTimer = null;

    this.init();
  }

  async init() {
    this.initSplashScreen();
    this.initTheme();
    this.initWindowControls();
    this.initTabNavigation();

    this.devicesView = new window.DevicesView(this);
    this.sendView = new window.SendView(this);
    this.receivedView = new window.ReceivedView(this);
    this.clipboardView = new window.ClipboardView(this);
    this.settingsView = new window.SettingsView(this);

    window.devicesView = this.devicesView;
    window.sendView = this.sendView;
    window.receivedView = this.receivedView;
    window.clipboardView = this.clipboardView;
    window.settingsView = this.settingsView;

    await this.refreshPeers();
    await this.refreshHistory();
    await this.settingsView.load();
    await this.checkPostUpdateChangelog();

    setTimeout(() => this.updateTabIndicator(), 100);
    window.addEventListener('resize', () => this.updateTabIndicator());

    this.initUpdatingListeners();

    // Polling loop for peers and history
    this.startPolling();
  }

  initSplashScreen() {
    const splash = document.getElementById('splash-screen');
    if (!splash) return;

    const minDisplayMs = 1500;
    const startTime = Date.now();

    const hideSplash = () => {
      const elapsed = Date.now() - startTime;
      const remaining = Math.max(0, minDisplayMs - elapsed);
      setTimeout(() => {
        splash.classList.add('splash-fade-out');
        setTimeout(() => {
          splash.style.display = 'none';
        }, 680);
      }, remaining);
    };

    if (document.readyState === 'complete') {
      hideSplash();
    } else {
      window.addEventListener('load', hideSplash, { once: true });
    }
  }

  initTheme() {
    const savedTheme = localStorage.getItem('skydrop-theme') || 'system';
    this.setTheme(savedTheme);

    // Listen for OS system theme changes
    if (window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
        const currentMode = localStorage.getItem('skydrop-theme') || 'system';
        if (currentMode === 'system') {
          this.applyTheme('system');
        }
      });
    }

    // Quick toggle titlebar button: cycles system -> dark -> light -> system
    const themeBtn = document.getElementById('btn-theme');
    if (themeBtn) {
      themeBtn.addEventListener('click', () => {
        const current = localStorage.getItem('skydrop-theme') || 'system';
        const next = current === 'system' ? 'dark' : (current === 'dark' ? 'light' : 'system');
        this.setTheme(next);
      });
    }

    // Segmented control button click listeners in Settings screen
    document.querySelectorAll('.theme-option').forEach((opt) => {
      opt.addEventListener('click', () => {
        const val = opt.getAttribute('data-theme-val');
        if (val) this.setTheme(val);
      });
    });
  }

  setTheme(mode) {
    localStorage.setItem('skydrop-theme', mode);
    this.applyTheme(mode);
    this.updateThemeSegmentedUI(mode);
  }

  applyTheme(mode) {
    let resolvedTheme = mode;
    if (mode === 'system') {
      const isSystemDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
      resolvedTheme = isSystemDark ? 'dark' : 'light';
    }
    document.documentElement.setAttribute('data-theme', resolvedTheme);

    const splashArt = document.querySelector('.splash-filaments-art');
    if (splashArt) {
      splashArt.src = resolvedTheme === 'light' ? 'assets/splash-filaments-light.svg' : 'assets/splash-filaments-dark.svg';
    }

    const themeBtn = document.getElementById('btn-theme');
    if (themeBtn) {
      const systemSvg = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>`;
      const darkSvg = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>`;
      const lightSvg = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>`;

      if (mode === 'system') {
        themeBtn.innerHTML = systemSvg;
        themeBtn.title = 'Theme: System Default';
      } else if (mode === 'dark') {
        themeBtn.innerHTML = darkSvg;
        themeBtn.title = 'Theme: Dark';
      } else {
        themeBtn.innerHTML = lightSvg;
        themeBtn.title = 'Theme: Light';
      }
    }
  }

  updateThemeSegmentedUI(mode) {
    document.querySelectorAll('.theme-option').forEach((opt) => {
      const optMode = opt.getAttribute('data-theme-val');
      opt.classList.toggle('active', optMode === mode);
    });
  }

  initWindowControls() {
    document.getElementById('btn-minimize').addEventListener('click', () => {
      window.skydrop.minimizeWindow();
    });

    document.getElementById('btn-close').addEventListener('click', () => {
      window.skydrop.closeWindow();
    });
  }

  initTabNavigation() {
    const tabButtons = document.querySelectorAll('.tab-btn');
    tabButtons.forEach((btn) => {
      btn.addEventListener('click', () => {
        const targetScreen = btn.getAttribute('data-screen');
        this.switchTab(targetScreen);
      });
    });
  }

  async initStatusBarAndUpdater() {
    const versionEl = document.getElementById('statusbar-version');
    const settingsVersionEl = document.getElementById('settings-app-version');
    const updateBanner = document.getElementById('update-banner');
    const updateBannerText = document.getElementById('update-banner-text');

    let currentVersion = '1.0.19';
    if (window.skydrop && typeof window.skydrop.getAppVersion === 'function') {
      try {
        currentVersion = await window.skydrop.getAppVersion();
      } catch (_) {}
    }

    if (versionEl) {
      versionEl.textContent = `v${currentVersion}`;
    }

    if (settingsVersionEl) {
      settingsVersionEl.textContent = `High-speed local peer-to-peer drop • v${currentVersion}`;
    }

    let releaseUrl = 'https://github.com/PMGEECODE/skydrop/releases/latest';

    const checkUpdatesInBackground = async () => {
      try {
        if (window.skydrop && typeof window.skydrop.checkForUpdates === 'function') {
          const res = await window.skydrop.checkForUpdates();
          if (res && res.updateAvailable && updateBanner && updateBannerText) {
            releaseUrl = res.releaseUrl || releaseUrl;
            updateBannerText.textContent = `Update v${res.latestVersion} Available`;
            updateBanner.style.display = 'inline-flex';
            updateBanner.title = `Click to view and download v${res.latestVersion} on GitHub`;
            updateBanner.onclick = () => {
              if (window.skydrop && typeof window.skydrop.openPath === 'function') {
                window.skydrop.openPath(releaseUrl);
              }
            };
          }
        }
      } catch (err) {
        console.warn('Background update check error:', err);
      }
    };

    // Automatic background update check on startup (3s after launch)
    setTimeout(() => {
      checkUpdatesInBackground();
    }, 3000);
  }

  switchTab(screenId) {
    this.activeScreen = screenId;
    document.querySelectorAll('.screen').forEach((s) => s.classList.remove('active'));
    document.querySelectorAll('.tab-btn').forEach((b) => b.classList.remove('active'));

    const screen = document.getElementById(screenId);
    if (screen) screen.classList.add('active');

    const tabBtn = document.querySelector(`.tab-btn[data-screen="${screenId}"]`);
    if (tabBtn) tabBtn.classList.add('active');

    this.updateTabIndicator();

    if (screenId === 'screen-settings') {
      this.settingsView.load();
    } else if (screenId === 'screen-clipboard') {
      if (this.clipboardView) this.clipboardView.load();
    } else if (screenId === 'screen-received') {
      this.refreshHistory();
    } else if (screenId === 'screen-devices') {
      this.refreshPeers();
    }
  }

  updateTabIndicator() {
    const activeBtn = document.querySelector('.tab-btn.active');
    const indicator = document.getElementById('tab-active-indicator');
    const tabBar = document.getElementById('tab-bar');
    if (!activeBtn || !indicator || !tabBar) return;

    const barRect = tabBar.getBoundingClientRect();
    const btnRect = activeBtn.getBoundingClientRect();

    if (btnRect.width === 0) return;

    const indicatorWidth = 48;
    const targetLeft = (btnRect.left - barRect.left) + (btnRect.width / 2) - (indicatorWidth / 2);

    indicator.style.transform = `translateX(${targetLeft}px)`;
  }

  async refreshPeers() {
    try {
      const res = await window.skydrop.getPeers();
      if (res.ok && res.data) {
        this.devicesView.render(res.data);
        this.sendView.updatePeers(res.data);
      }
    } catch (err) {
      console.warn('Failed to refresh peers:', err);
    }
  }

  async refreshHistory() {
    try {
      const res = await window.skydrop.getHistory();
      if (res.ok && Array.isArray(res.data)) {
        this.receivedView.render(res.data);
      }
    } catch (err) {
      console.warn('Failed to refresh history:', err);
    }
  }

  startPolling() {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.pollTimer = setInterval(async () => {
      // Avoid polling while window is hidden or minimized
      if (typeof document !== 'undefined' && document.hidden) return;
      await this.refreshPeers();
      await this.refreshHistory();
    }, 4000);

    // Immediate refresh when user switches back to this window
    if (typeof window !== 'undefined') {
      window.addEventListener('focus', () => {
        this.refreshPeers();
        this.refreshHistory();
      });

      // Event-driven auto-navigation and refresh when receiving files
      if (window.skydrop && typeof window.skydrop.onReceiveProgress === 'function') {
        let lastReceiveSession = null;
        window.skydrop.onReceiveProgress((prog) => {
          if (!prog) return;
          if (prog.status === 'receiving' || prog.status === 'completed') {
            const sessionId = prog.filename || 'active';
            if (lastReceiveSession !== sessionId) {
              lastReceiveSession = sessionId;
              if (this.activeScreen !== 'screen-received') {
                this.switchTab('screen-received');
              }
            }
            if (prog.status === 'completed') {
              setTimeout(() => {
                this.refreshHistory();
                lastReceiveSession = null;
              }, 400);
            }
          }
        });
      }

      // Event-driven refresh when a file transfer finishes
      if (window.skydrop && typeof window.skydrop.onTransferProgress === 'function') {
        window.skydrop.onTransferProgress((prog) => {
          if (prog && (prog.percent === 100 || prog.status === 'completed')) {
            setTimeout(() => this.refreshHistory(), 400);
          }
        });
      }
    }
  }

  compareVersions(v1, v2) {
    const clean = (v) => (v || '').replace(/^[a-z-]+/i, '').replace(/^v/i, '').trim();
    const s1 = clean(v1);
    const s2 = clean(v2);
    const [c1] = s1.split('-');
    const [c2] = s2.split('-');
    const p1 = c1.split('.').map((n) => parseInt(n, 10) || 0);
    const p2 = c2.split('.').map((n) => parseInt(n, 10) || 0);
    const len = Math.max(p1.length, p2.length);
    for (let i = 0; i < len; i++) {
      const a = p1[i] || 0;
      const b = p2[i] || 0;
      if (a > b) return 1;
      if (a < b) return -1;
    }
    return 0;
  }

  async checkPostUpdateChangelog() {
    try {
      const currentVer = await window.skydrop.getAppVersion();
      const lastVer = localStorage.getItem('skydrop-last-seen-version');

      if (!lastVer) {
        localStorage.setItem('skydrop-last-seen-version', currentVer);
        return;
      }

      if (this.compareVersions(currentVer, lastVer) > 0) {
        this.showChangelogModal(currentVer, lastVer);
      }
    } catch (err) {
      console.warn('[SkyDropApp] Changelog check error:', err);
    }
  }

  showChangelogModal(newVer, oldVer) {
    const modal = document.getElementById('changelog-modal');
    const versionTag = document.getElementById('changelog-version-tag');
    const content = document.getElementById('changelog-content');
    const btnSkip = document.getElementById('btn-changelog-skip');
    const btnClose = document.getElementById('btn-changelog-close');

    if (!modal) return;
    if (versionTag) versionTag.textContent = `v${newVer}`;

    const savedNotes = localStorage.getItem('skydrop-pending-notes');
    let html = '';

    if (savedNotes && savedNotes.trim().length > 10) {
      html = `<div style="white-space: pre-line;">${savedNotes}</div>`;
    } else {
      html = `
        <div class="changelog-list">
          <div class="changelog-item">
            <span class="changelog-item-icon">🔄</span>
            <div class="changelog-item-text">
              <h5>Automatic Background Updates</h5>
              <p>SkyDrop now automatically checks for new releases in the background and delivers instant desktop notifications.</p>
            </div>
          </div>
          <div class="changelog-item">
            <span class="changelog-item-icon">⚡</span>
            <div class="changelog-item-text">
              <h5>Instant PIN Pairing & Focus</h5>
              <p>PIN modal captures keyboard input and focus immediately with zero latency.</p>
            </div>
          </div>
          <div class="changelog-item">
            <span class="changelog-item-icon">📺</span>
            <div class="changelog-item-text">
              <h5>Enhanced TV & Mobile Synchronization</h5>
              <p>Optimized mDNS discovery, live presence indicators, and seamless large file transfers.</p>
            </div>
          </div>
          <div class="changelog-item">
            <span class="changelog-item-icon">🛡️</span>
            <div class="changelog-item-text">
              <h5>Reliability & Performance</h5>
              <p>Dynamic multi-release filtering, hardened daemon supervision, and refined dark UI.</p>
            </div>
          </div>
        </div>
      `;
    }

    if (content) content.innerHTML = html;
    modal.style.display = 'flex';

    const dismiss = () => {
      localStorage.setItem('skydrop-last-seen-version', newVer);
      localStorage.removeItem('skydrop-pending-notes');
      modal.style.display = 'none';
    };

    if (btnSkip) btnSkip.onclick = dismiss;
    if (btnClose) btnClose.onclick = dismiss;
  }

  initUpdatingListeners() {
    if (window.skydrop && typeof window.skydrop.onShowUpdatingStatus === 'function') {
      window.skydrop.onShowUpdatingStatus((data) => {
        const overlay = document.getElementById('updating-overlay');
        const text = document.getElementById('updating-overlay-text');
        if (overlay) {
          if (text && data && data.version) {
            text.textContent = `Installing SkyDrop v${data.version}... The application will restart automatically.`;
          }
          overlay.style.display = 'flex';
        }
      });
    }
  }
}

document.addEventListener('DOMContentLoaded', () => {
  window.app = new SkyDropApp();
});
