const { Tray, Menu, app, shell } = require('electron');
const path = require('path');
const fs = require('fs');

/**
 * Manages the Linux system tray icon and background persistence.
 */
class TrayManager {
  constructor(mainWindow, getStorageDir) {
    this.mainWindow = mainWindow;
    this.getStorageDir = getStorageDir || (() => path.join(app.getPath('downloads'), 'SkyDrop'));
    this.tray = null;
  }

  resolveTrayIcon() {
    const candidatePaths = [
      path.join(__dirname, '../assets/tray.png'),
      path.join(__dirname, '../assets/icon.png'),
      process.resourcesPath ? path.join(process.resourcesPath, 'assets/tray.png') : null
    ].filter(Boolean);
    for (const p of candidatePaths) {
      if (fs.existsSync(p)) {
        return p;
      }
    }
    return null;
  }

  init() {
    const iconPath = this.resolveTrayIcon();
    if (!iconPath) {
      console.warn('[TrayManager] Tray icon could not be located.');
      return;
    }

    try {
      this.tray = new Tray(iconPath);
      this.tray.setToolTip('SkyDrop - Fast Local File Drop');

      const updateContextMenu = () => {
        const isVisible = this.mainWindow && this.mainWindow.isVisible();
        const contextMenu = Menu.buildFromTemplate([
          {
            label: isVisible ? 'Hide Window' : 'Show SkyDrop',
            click: () => {
              if (this.mainWindow) {
                if (this.mainWindow.isVisible()) {
                  this.mainWindow.hide();
                } else {
                  this.mainWindow.show();
                  this.mainWindow.focus();
                }
              }
            }
          },
          {
            label: 'Open Downloads Folder',
            click: () => {
              const dir = this.getStorageDir();
              if (fs.existsSync(dir)) {
                shell.openPath(dir);
              } else {
                fs.mkdirSync(dir, { recursive: true });
                shell.openPath(dir);
              }
            }
          },
          { type: 'separator' },
          {
            label: 'Quit SkyDrop',
            click: () => {
              app.isQuitting = true;
              app.quit();
            }
          }
        ]);
        this.tray.setContextMenu(contextMenu);
      };

      updateContextMenu();

      this.tray.on('click', () => {
        if (!this.mainWindow) return;
        if (this.mainWindow.isVisible()) {
          this.mainWindow.hide();
        } else {
          this.mainWindow.show();
          this.mainWindow.focus();
        }
        updateContextMenu();
      });

      if (this.mainWindow) {
        this.mainWindow.on('show', updateContextMenu);
        this.mainWindow.on('hide', updateContextMenu);
      }
    } catch (err) {
      console.error('[TrayManager] Failed to create system tray:', err);
    }
  }

  destroy() {
    if (this.tray) {
      this.tray.destroy();
      this.tray = null;
    }
  }
}

module.exports = TrayManager;
