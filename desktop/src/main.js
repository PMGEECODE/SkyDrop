const { app, BrowserWindow, nativeTheme, powerMonitor, protocol } = require('electron');
const path = require('path');
const DaemonManager = require('./daemon-manager');
const TrayManager = require('./tray-manager');
const ThumbnailManager = require('./thumbnail-manager');
const { registerMediaProtocol } = require('./media-protocol');
const { registerIpcHandlers } = require('./ipc-handlers');

let mainWindow = null;
let daemonManager = null;
let trayManager = null;
let thumbnailManager = null;
app.isQuitting = false;

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
    resizable: true,
    show: false,
    backgroundColor: '#00000000',
    icon: path.join(__dirname, '../assets/icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'ui/index.html'));

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

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

  createWindow();

  registerIpcHandlers(mainWindow, daemonManager, thumbnailManager);

  // Start Go daemon child process or connect to existing
  await daemonManager.start();

  trayManager = new TrayManager(mainWindow);
  trayManager.init();

  if (powerMonitor) {
    powerMonitor.on('resume', () => {
      console.log('[powerMonitor] System resumed from sleep - re-announcing and probing peers');
      if (daemonManager) {
        daemonManager.request('/api/presence/retry', { method: 'POST' }).catch(() => {});
      }
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
