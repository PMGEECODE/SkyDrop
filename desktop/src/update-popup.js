const { BrowserWindow, screen, ipcMain } = require('electron');
const path = require('path');

let popupWindow = null;
let currentUpdateData = null;
let onStartUpdateCallback = null;

/**
 * Creates and displays a non-intrusive update notification popup
 * positioned at the bottom-right corner of the user's primary display work area.
 */
function showUpdatePopup(updateData, onStartUpdate) {
  if (!updateData || !updateData.updateAvailable) return;
  currentUpdateData = updateData;
  if (onStartUpdate) onStartUpdateCallback = onStartUpdate;

  if (popupWindow && !popupWindow.isDestroyed()) {
    popupWindow.focus();
    popupWindow.webContents.send('init-update-data', updateData);
    return;
  }

  const primaryDisplay = screen.getPrimaryDisplay();
  const { x: areaX, y: areaY, width: areaWidth, height: areaHeight } = primaryDisplay.workArea;

  const popupWidth = 360;
  const popupHeight = 160;
  const x = Math.round(areaX + areaWidth - popupWidth - 20);
  const y = Math.round(areaY + areaHeight - popupHeight - 20);

  popupWindow = new BrowserWindow({
    width: popupWidth,
    height: popupHeight,
    x,
    y,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    show: false,
    backgroundColor: '#00000000',
    webPreferences: {
      preload: path.join(__dirname, 'preload-popup.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  popupWindow.loadFile(path.join(__dirname, 'ui/update-popup.html'));

  popupWindow.once('ready-to-show', () => {
    if (popupWindow && !popupWindow.isDestroyed()) {
      popupWindow.showInactive();
      popupWindow.webContents.send('init-update-data', currentUpdateData);
    }
  });

  popupWindow.on('closed', () => {
    popupWindow = null;
  });
}

function sendDownloadProgressToPopup(progress) {
  if (popupWindow && !popupWindow.isDestroyed()) {
    popupWindow.webContents.send('download-progress', progress);
  }
}

function closeUpdatePopup() {
  if (popupWindow && !popupWindow.isDestroyed()) {
    popupWindow.close();
  }
  popupWindow = null;
}

function registerUpdatePopupIpc(onStartUpdateHandler) {
  if (onStartUpdateHandler) {
    onStartUpdateCallback = onStartUpdateHandler;
  }

  ipcMain.handle('start-update-from-popup', async () => {
    if (typeof onStartUpdateCallback === 'function') {
      return onStartUpdateCallback(currentUpdateData);
    }
  });

  ipcMain.handle('dismiss-update-popup', () => {
    closeUpdatePopup();
    return { ok: true };
  });
}

module.exports = {
  showUpdatePopup,
  sendDownloadProgressToPopup,
  closeUpdatePopup,
  registerUpdatePopupIpc
};
