## What's New in v1.0.42

- **Pairing PIN Responsiveness**: Removed daemon status polling sleep latency and added immediate focus, inputmode, and key routing to the desktop PIN entry dialog.
- **Dynamic Dropzone on Send Screen**: Automatically hides the dropzone when files are queued to reclaim vertical space, while preserving full drag-and-drop support across the entire screen.

### 🪟 Windows Desktop Support & NSIS Installer
- **Official 64-bit Windows Installer**: Added `skydrop-1.0.41-win-x64-setup.exe` (NSIS installer) featuring Desktop shortcut, Start Menu integration, and clean uninstaller support.
- **Headless Background Go Daemon**: Cross-compiled `skydrop-daemon.exe` with GUI subsystem flags (`-H windowsgui`) for background file transfers without console window popups.
- **Windows Autostart Integration**: Integrated native Windows user login startup via Electron's `setLoginItemSettings()`.
- **In-App Updater**: Automated background update checks, SHA-256 integrity verification, and silent installer updates directly from the app.

### 🎨 Design System & Theme Consistency
- **Harmonized Window Geometry**: Standardized corner radius tokens (`--radius-window`, `--radius-lg`, `--radius-md`, `--radius-sm`) across all views, modals, cards, and list items.
- **Adaptive Navigation Bar**: Refined bottom navigation tabs to adhere strictly to active Light and Dark themes.
- **Ambient Visuals**: Added a non-intrusive theme-aware wavy strand background and dedicated animated splash screen filaments.

### 🐧 Linux Enhancements
- Updated 64-bit Debian package (`.deb`) and standalone portable `AppImage`.
- Fully automated multi-platform release staging pipeline with SHA-256 integrity verification.
