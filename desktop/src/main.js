const { app, BrowserWindow, nativeTheme, powerMonitor, protocol } = require('electron');
const path = require('path');
const DaemonManager = require('./daemon-manager');
const TrayManager = require('./tray-manager');
const ThumbnailManager = require('./thumbnail-manager');
const { registerMediaProtocol } = require('./media-protocol');
const { registerIpcHandlers } = require('./ipc-handlers');
const AutostartManager = require('./autostart-manager');
const { Updater } = require('./updater');
const {
  showUpdatePopup,
  sendDownloadProgressToPopup,
  closeUpdatePopup,
  registerUpdatePopupIpc
} = require('./update-popup');

let mainWindow = null;
let daemonManager = null;
let trayManager = null;
let thumbnailManager = null;
let autostartManager = null;
let updater = null;
app.isQuitting = false;

// Enable Linux window transparency switches before app is ready
app.commandLine.appendSwitch('enable-transparent-visuals');
app.disableHardwareAcceleration();

// Register privileged custom media scheme before app is ready
protocol.registerSchemesAsPrivileged([
  {
    scheme: 'skydrop-media',
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      stream: true,
      bypassCSP: true
    }
  }
]);

// Enforce single instance lock
const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 520,
    height: 700,
    minWidth: 520,
    maxWidth: 520,
    minHeight: 520,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    hasShadow: false,
    resizable: false,
    show: false,
    icon: path.join(__dirname, '../assets/icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'ui/index.html'));

  const isHiddenLaunch = process.argv.includes('--hidden') || process.argv.includes('--minimized') || process.argv.includes('--start-hidden');
  if (!isHiddenLaunch) {
    mainWindow.once('ready-to-show', () => {
      mainWindow.show();
    });
  }

  mainWindow.on('close', (event) => {
    if (!app.isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
}

// Ignore self-signed TLS errors from local SkyDrop Go daemon
app.on('certificate-error', (event, webContents, url, error, certificate, callback) => {
  if (url.startsWith('https://127.0.0.1') || url.startsWith('https://localhost')) {
    event.preventDefault();
    callback(true);
  } else {
    callback(false);
  }
});

app.whenReady().then(async () => {
  registerMediaProtocol();
  thumbnailManager = new ThumbnailManager();

  daemonManager = new DaemonManager({
    port: 8443,
    useTLS: true,
    onStatusChange: (status) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('daemon-status', status);
      }
    }
  });

  autostartManager = new AutostartManager({ app });
  autostartManager.syncOnStartup();

  updater = new Updater({ app });

  createWindow();

  registerIpcHandlers(mainWindow, daemonManager, thumbnailManager, autostartManager, updater);

  // Start Go daemon child process or connect to existing
  await daemonManager.start();

  trayManager = new TrayManager(mainWindow);
  trayManager.init();

  async function handleDownloadAndInstall(updateData) {
    try {
      const result = await updater.downloadAndInstall((progress) => {
        sendDownloadProgressToPopup(progress);
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send('update-progress', progress);
        }
      });

      if (result && result.ok) {
        closeUpdatePopup();
        if (mainWindow && !mainWindow.isDestroyed()) {
          if (mainWindow.isMinimized()) mainWindow.restore();
          mainWindow.show();
          mainWindow.focus();
          mainWindow.webContents.send('show-updating-status', {
            version: (updateData && updateData.latestVersion) || result.installedVersion || ''
          });
        }
        setTimeout(() => {
          updater.restartApp();
        }, 1600);
        return { ok: true };
      }
      return result;
    } catch (err) {
      console.error('[Updater] Download & install failed:', err);
      return { ok: false, error: err.message };
    }
  }

  registerUpdatePopupIpc(handleDownloadAndInstall);

  async function checkUpdatesInBackground() {
    try {
      const update = await updater.checkGitHubUpdate();
      if (update && update.ok && update.updateAvailable) {
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send('update-available', update);
        }
        showUpdatePopup(update, handleDownloadAndInstall);
      }
    } catch (err) {
      console.warn('[Updater] Background check error:', err.message);
    }
  }

  // Automatic background update checking: launch check after 3.5s
  setTimeout(checkUpdatesInBackground, 3500);

  // Periodic background update check every 1 hour
  setInterval(checkUpdatesInBackground, 3600000);

  if (powerMonitor) {
    powerMonitor.on('resume', () => {
      console.log('[powerMonitor] System resumed from sleep - re-announcing and checking updates');
      if (daemonManager) {
        daemonManager.request('/api/presence/retry', { method: 'POST' }).catch(() => {});
      }
      setTimeout(checkUpdatesInBackground, 8000);
    });
    powerMonitor.on('suspend', () => {
      console.log('[powerMonitor] System entering sleep');
    });
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    } else if (mainWindow) {
      mainWindow.show();
      mainWindow.focus();
    }
  });
});

app.on('before-quit', () => {
  app.isQuitting = true;
  if (thumbnailManager) {
    thumbnailManager.destroy();
  }
  if (daemonManager) {
    daemonManager.stop();
  }
});

app.on('window-all-closed', () => {
  // On Linux/Windows, keep running in background tray when window is closed
  if (process.platform === 'darwin') {
    app.dock.hide();
  }
});
