# SkyDrop

**SkyDrop** is a fast, private, peer-to-peer local network file sharing and device synchronization platform for Linux, Windows, and Android. It enables instant device discovery, high-throughput encrypted file transfers, shared clipboard synchronization, and Wi-Fi hotspot pairing across your local network without intermediate cloud servers or third-party telemetry.

---

## Architecture Overview

SkyDrop is organized into a modular monorepo:

```
skydrop/
├── daemon/               # High-performance Go background daemon
│   ├── cmd/daemon/       # Entrypoint executable
│   └── internal/         # Discovery (mDNS), TLS transport, pairing, clipboard, hotspot
├── desktop/              # Cross-platform Electron desktop application
│   ├── src/              # Main process, IPC handlers, daemon manager, autostart, updater
│   ├── src/ui/           # Responsive frontend (light/dark themes, grid, lightbox)
│   ├── assets/           # Application icons, wordmarks, and visual assets
│   └── tests/            # Automated integration & unit test suite
├── Android-app/          # Native Android client (Jetpack Compose & Kotlin)
├── branding/             # Vector icons, official logos, and typography guidelines
├── docs/                 # Architectural specifications, protocol docs, and design blueprints
└── .github/workflows/    # Automated CI/CD and multi-platform release pipelines
```

### Components

1. **Daemon (`daemon/`)**
   - Written in Go for minimal memory footprint and maximum I/O throughput.
   - Handles local mDNS peer discovery, mutual TLS (mTLS) encrypted transfers, one-time PIN pairing, auto-accept verification, and Wi-Fi hotspot orchestration.
   - Headless execution with background daemon lifecycle management.

2. **Desktop Client (`desktop/`)**
   - Built with Electron, HTML5, CSS custom properties (design tokens), and modern async JavaScript.
   - Fixed 520px compact window with system tray integration, smooth theme switching (Dark/Light/System), interactive PIN approval modals, responsive received media grid with lazy thumbnailing, and diagnostics.
   - Built-in updater supporting Linux (`.deb`, `.AppImage`) and Windows (`.exe` NSIS installers) with SHA-256 verification.

3. **Android Application (`Android-app/`)**
   - Native mobile client ensuring seamless cross-device sharing between workstations and mobile devices.

---

## Getting Started

### Prerequisites

- **Go**: 1.22 or higher
- **Node.js**: 20 LTS or higher
- **npm**: 10 or higher
- **Git**

### Installation & Development

1. **Clone the repository**:
   ```bash
   git clone https://github.com/PMGEECODE/skydrop.git
   cd skydrop
   ```

2. **Install desktop dependencies**:
   ```bash
   cd desktop
   npm install
   ```

3. **Compile the Go daemon**:
   - For Linux:
     ```bash
     npm run build:daemon
     ```
   - For Windows:
     ```bash
     npm run build:daemon:win
     ```
   - For all platforms:
     ```bash
     npm run build:daemon:all
     ```

4. **Launch the desktop application**:
   ```bash
   npm start
   # Or for development with live dev tools:
   npm run dev
   ```

---

## Testing

SkyDrop features a comprehensive 26-stage automated test suite covering daemon resolution, health checks, package configs, assets, change detection, responsive grid layout, window border tokens, pairing workflows, autostart managers, updater logic, and CI workflow specifications:

```bash
npm test --prefix desktop
```

To run Go daemon unit tests:
```bash
cd daemon
go test -v ./...
```

---

## Building Distribution Packages

Distribution packages are built using `electron-builder` and placed into `desktop/dist/`:

| Platform | Command | Output Artifact |
| :--- | :--- | :--- |
| **Linux (All)** | `npm run dist` | `skydrop-<version>-linux-amd64.deb`<br>`skydrop-<version>-linux-x86_64.AppImage` |
| **Linux (Debian / Ubuntu)** | `npm run dist:deb` | `skydrop-<version>-linux-amd64.deb` |
| **Linux (AppImage)** | `npm run dist:appimage` | `skydrop-<version>-linux-x86_64.AppImage` |
| **Windows (x64)** | `npm run dist:win` | `skydrop-<version>-win-x64-setup.exe` |
| **All Desktop Targets** | `npm run dist:all` | Builds deb, AppImage, and Windows installer concurrently |

---

## Automated Release Pipeline

GitHub Actions is configured in [`.github/workflows/release.yml`](.github/workflows/release.yml) to automatically compile, verify, and publish releases whenever a version tag is pushed:

```bash
# Example cutting a new release:
git tag desktop-v1.1.0
git push origin desktop-v1.1.0
```

1. **Build Matrix**: Linux (`ubuntu-latest`) builds `.deb` and `.AppImage`; Windows (`windows-latest`) builds the NSIS installer.
2. **Integrity Verification**: Generates `sha256sums.txt` covering all installer binaries.
3. **Publication**: Automatically publishes the finished binaries and checksums to the public [`PMGEECODE/skydrop-release`](https://github.com/PMGEECODE/skydrop-release) repository without exposing any source code.
4. **Dry-Run Mode**: Manual runs via `workflow_dispatch` support a `dry_run` option for end-to-end verification without publishing.

---

## Security & Privacy

- **Local-Only Communication**: All transfers, discovery probes, and clipboard payloads stay strictly within your local area network (LAN / Wi-Fi Direct).
- **Zero Cloud Dependence**: No external accounts, relays, telemetry, or server storage are required.
- **PIN Pairing & Verification**: Auto-accept and sensitive operations require interactive mutual authorization with temporary PIN verification.

---

## License

This project is licensed under the [MIT License](LICENSE).
