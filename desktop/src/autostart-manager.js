const fs = require('fs');
const path = require('path');
const os = require('os');

class AutostartManager {
  constructor(options = {}) {
    this.app = options.app || null;
    this.autostartDir = options.autostartDir || path.join(os.homedir(), '.config', 'autostart');
    this.desktopFilePath = options.desktopFilePath || path.join(this.autostartDir, 'skydrop.desktop');
    this.configDir = options.configDir || (this.app ? this.app.getPath('userData') : path.join(os.homedir(), '.config', 'skydrop'));
    this.settingsFilePath = options.settingsFilePath || path.join(this.configDir, 'desktop-settings.json');
    this.customExecPath = options.execPath || null;
    this.customIconPath = options.iconPath || null;
  }

  getExecPath() {
    if (this.customExecPath) {
      return this.customExecPath;
    }
    // If running from an AppImage, process.env.APPIMAGE holds the true persistent file path
    if (process.env.APPIMAGE) {
      return process.env.APPIMAGE;
    }
    // If running as packaged binary (e.g. deb installation in /usr/bin or /opt/SkyDrop)
    if (this.app && this.app.isPackaged) {
      return process.execPath;
    }
    // In development mode
    return process.execPath;
  }

  getIconPath() {
    if (this.customIconPath) {
      return this.customIconPath;
    }
    const defaultIcon = path.resolve(__dirname, '../assets/icon.png');
    if (fs.existsSync(defaultIcon)) {
      return defaultIcon;
    }
    return 'skydrop';
  }

  generateDesktopContent() {
    const rawExec = this.getExecPath();
    // Quote executable path to safely handle spaces
    const quotedExec = rawExec.includes(' ') && !rawExec.startsWith('"') ? `"${rawExec}"` : rawExec;
    const execWithArg = `${quotedExec} --hidden`;
    const icon = this.getIconPath();

    return `[Desktop Entry]
Type=Application
Name=SkyDrop
Comment=SkyDrop Local File Drop
Exec=${execWithArg}
Icon=${icon}
Terminal=false
Categories=Network;FileTransfer;
X-GNOME-Autostart-enabled=true
`;
  }

  getSettings() {
    try {
      if (fs.existsSync(this.settingsFilePath)) {
        const data = fs.readFileSync(this.settingsFilePath, 'utf8');
        return JSON.parse(data) || {};
      }
    } catch (err) {
      console.warn('[AutostartManager] Failed to read desktop settings:', err.message);
    }
    return {};
  }

  saveSettings(settings) {
    try {
      if (!fs.existsSync(this.configDir)) {
        fs.mkdirSync(this.configDir, { recursive: true });
      }
      const existing = this.getSettings();
      const updated = { ...existing, ...settings };
      fs.writeFileSync(this.settingsFilePath, JSON.stringify(updated, null, 2) + '\n', 'utf8');
      return true;
    } catch (err) {
      console.error('[AutostartManager] Failed to save desktop settings:', err.message);
      return false;
    }
  }

  getPreference() {
    const settings = this.getSettings();
    if (typeof settings.autostart === 'boolean') {
      return settings.autostart;
    }
    return this.isEnabled();
  }

  isEnabled() {
    try {
      if (!fs.existsSync(this.desktopFilePath)) {
        return false;
      }
      const content = fs.readFileSync(this.desktopFilePath, 'utf8');
      if (content.includes('X-GNOME-Autostart-enabled=false')) {
        return false;
      }
      return content.includes('Type=Application') && content.includes('Exec=');
    } catch (err) {
      console.warn('[AutostartManager] Error checking autostart status:', err.message);
      return false;
    }
  }

  enable() {
    try {
      if (!fs.existsSync(this.autostartDir)) {
        fs.mkdirSync(this.autostartDir, { recursive: true });
      }
      const content = this.generateDesktopContent();
      fs.writeFileSync(this.desktopFilePath, content, { encoding: 'utf8', mode: 0o755 });
      this.saveSettings({ autostart: true });
      console.log(`[AutostartManager] Autostart enabled at ${this.desktopFilePath}`);
      return { ok: true, enabled: true };
    } catch (err) {
      console.error('[AutostartManager] Failed to enable autostart:', err.message);
      return { ok: false, error: err.message, enabled: this.isEnabled() };
    }
  }

  disable() {
    try {
      if (fs.existsSync(this.desktopFilePath)) {
        fs.unlinkSync(this.desktopFilePath);
      }
      this.saveSettings({ autostart: false });
      console.log('[AutostartManager] Autostart disabled and desktop file removed');
      return { ok: true, enabled: false };
    } catch (err) {
      console.error('[AutostartManager] Failed to disable autostart:', err.message);
      return { ok: false, error: err.message, enabled: this.isEnabled() };
    }
  }

  syncOnStartup() {
    const pref = this.getPreference();
    if (pref) {
      const isCurrentlyEnabled = this.isEnabled();
      let needsUpdate = !isCurrentlyEnabled;

      if (isCurrentlyEnabled) {
        try {
          const currentContent = fs.readFileSync(this.desktopFilePath, 'utf8');
          const expectedExec = this.getExecPath();
          if (!currentContent.includes(expectedExec)) {
            console.log('[AutostartManager] Executable path changed, updating autostart entry');
            needsUpdate = true;
          }
        } catch (_) {
          needsUpdate = true;
        }
      }

      if (needsUpdate) {
        console.log('[AutostartManager] Preference is ON; ensuring valid autostart entry exists...');
        this.enable();
      }
    }
  }
}

module.exports = AutostartManager;
