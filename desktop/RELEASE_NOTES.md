## What's New in v1.0.44

- **Automatic Background Updates**: SkyDrop now silently checks for new releases on startup, periodically every hour, and upon waking from sleep.
- **Desktop Update Notification Popup**: Frameless bottom-right desktop notification popup with live download progress and one-click "Update" action.
- **In-Flight Updating Overlay**: Full-window status display showing installation progress while restarting the application.
- **Post-Update Changelog Screen**: "What's Changed" modal on launch displaying release highlights with a quick "Skip" button.
- **Dynamic Dropzone**: Reclaims vertical space on the Send screen by hiding the dropzone once files are queued, preserving full drag-and-drop everywhere.
- **Instant PIN Pairing Focus**: Immediate focus and keyboard capture on the 6-digit pairing PIN dialog with zero delay.

### 🪟 Windows Desktop Support & NSIS Installer
- **Official 64-bit Windows Installer**: `skydrop-1.0.44-win-x64-setup.exe` (NSIS installer) featuring Desktop shortcut, Start Menu integration, and clean uninstaller support.
- **Headless Background Go Daemon**: Cross-compiled `skydrop-daemon.exe` with GUI subsystem flags (`-H windowsgui`) for background file transfers without console window popups.
- **Windows Autostart Integration**: Integrated native Windows user login startup via Electron's `setLoginItemSettings()`.

### 🎨 Design System & Theme Consistency
- **Harmonized Window Geometry**: Standardized corner radius tokens across all views, modals, cards, and list items.
- **Adaptive Navigation Bar**: Refined bottom navigation tabs adhering strictly to active Light and Dark themes.

### 🐧 Linux Enhancements
- Updated 64-bit Debian package (`.deb`) and standalone portable `AppImage`.
- Fully automated multi-platform release staging pipeline with SHA-256 integrity verification.
