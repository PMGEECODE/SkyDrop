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
    this.settingsView = new window.SettingsView(this);

    window.devicesView = this.devicesView;
    window.sendView = this.sendView;
    window.receivedView = this.receivedView;
    window.settingsView = this.settingsView;

    await this.refreshPeers();
    await this.refreshHistory();
    await this.settingsView.load();

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
    const savedTheme = localStorage.getItem('skydrop-theme') || 'dark';
    document.documentElement.setAttribute('data-theme', savedTheme);
    const themeBtn = document.getElementById('btn-theme');
    themeBtn.textContent = savedTheme === 'dark' ? '🌙' : '☀️';

    themeBtn.addEventListener('click', () => {
      const current = document.documentElement.getAttribute('data-theme');
      const next = current === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('skydrop-theme', next);
      themeBtn.textContent = next === 'dark' ? '🌙' : '☀️';
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

  switchTab(screenId) {
    this.activeScreen = screenId;
    document.querySelectorAll('.screen').forEach((s) => s.classList.remove('active'));
    document.querySelectorAll('.tab-btn').forEach((b) => b.classList.remove('active'));

    const screen = document.getElementById(screenId);
    if (screen) screen.classList.add('active');

    const tabBtn = document.querySelector(`.tab-btn[data-screen="${screenId}"]`);
    if (tabBtn) tabBtn.classList.add('active');

    if (screenId === 'screen-settings') {
      this.settingsView.load();
    } else if (screenId === 'screen-received') {
      this.refreshHistory();
    } else if (screenId === 'screen-devices') {
      this.refreshPeers();
    }
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
}

document.addEventListener('DOMContentLoaded', () => {
  window.app = new SkyDropApp();
});
