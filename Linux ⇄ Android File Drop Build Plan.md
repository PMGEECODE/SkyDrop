# Linux ⇄ Android File Drop: Architecture and Build Plan

## Goal

An AirDrop-style, bidirectional file sharing tool between a Linux laptop and an Android phone. No cables, no manual IP addresses, no repeated pairing. Share a file from either side and it lands in a configured folder on the other.

## Recommended Tech Stack

**Linux side: Go.** Compiles to a single static binary, very fast, tiny memory footprint, excellent standard library for HTTP and TLS, and mature mDNS libraries (grandcat/zeroconf or hashicorp/mdns). Runs as a systemd user service.

**Android side: Kotlin with Jetpack Compose.** Native Kotlin gives you first-class access to the Share menu, background services, and Android's NsdManager for discovery. OkHttp handles transfers.

**Desktop UI (Linux): system tray plus notifications.** Use a Go tray library such as fyne.io/systray, plus desktop notifications via libnotify (godbus). A small settings page served locally at localhost lets you choose the destination folder, view paired devices, and send files to the phone by drag and drop.

**Transport: HTTPS over the local network** with a self-signed certificate pinned during pairing. Simple, fast, and works with any HTTP client.

**Discovery: mDNS / DNS-SD.** The laptop advertises a service such as \_filedrop.\_tcp. The phone finds it with NsdManager. No IP typing, and it survives IP changes.

## Architecture Overview

Both devices run the same two roles, which is what makes it bidirectional.

- **Server role:** listens for incoming files over HTTPS and saves them to the configured folder.
- **Client role:** discovers the other device and uploads files to it.

The laptop runs the Go daemon with both roles. The phone runs a foreground service with a small embedded HTTP server (for example Ktor or NanoHTTPD) as the receiver, and the app UI plus Share target as the sender.

## Pairing and Security

1. The laptop shows a QR code in its settings page containing its device name, certificate fingerprint, and a one-time secret.
2. The phone scans it. Both sides exchange and store each other's certificate fingerprints and a long-lived shared token.
3. Every later transfer uses certificate pinning plus the token in an Authorization header. No re-pairing, no prompts.
4. Unpaired devices are rejected, so nobody else on the Wi-Fi can push files to you.

## Core API (both sides)

- GET /info: returns device name and version.
- POST /upload: multipart or raw streamed body, with filename and size in headers. Returns success and the saved path.
- Optional: POST /text for sharing links or clipboard text.

Stream uploads in chunks so large files do not sit in memory. Write to a temporary file, then rename on completion.

## Phone to Laptop Flow

1. In any Android app, tap Share and choose your app.
2. The app's share activity receives the file URI, shows the discovered laptop, and streams it over HTTPS.
3. The Go daemon writes it to the configured folder and shows a desktop notification with an Open button.

## Laptop to Phone Flow

1. Drag a file onto the tray window or local settings page, or use a file manager Send To action or a CLI command.
2. The daemon looks up the phone via mDNS and uploads to the phone's embedded server.
3. The phone's foreground service saves it into a chosen folder (for example Downloads/FileDrop) using MediaStore or the Storage Access Framework, then posts a notification.

## Android Gotchas to Plan For

- Run the receiver as a foreground service with a persistent low-priority notification, or Android will kill it.
- Request the NEARBY\_WIFI\_DEVICES permission on Android 13 and above, and handle multicast lock acquisition for mDNS.
- Use scoped storage correctly; do not rely on raw file paths.
- Offer a manual fallback (enter IP and port) in case a router blocks mDNS or has client isolation enabled.

## Build Phases

1. **Prototype the Go server:** upload endpoint, save to folder, mDNS advertising. Test with curl.
2. **Android sender:** discover the laptop, register as Share target, upload a file.
3. **Pairing:** QR code, certificate pinning, token auth.
4. **Android receiver:** foreground service with embedded server, so laptop to phone works.
5. **Laptop sender and UI:** tray icon, drag and drop, notifications, folder settings.
6. **Polish:** progress notifications, multiple files, resumable transfers, auto-start on boot, transfer history.

## Optional Alternatives

- **Flutter** for the phone app if you later want iOS too, though native Kotlin is better for the Share menu and background services.
- **Fork LocalSend** (open source, Flutter) if you decide you would rather customize something existing than build from scratch. Its protocol is a good reference for design.

## Suggested Project Layout

- /daemon: Go service (server, client, mDNS, tray)
- /android: Kotlin app
- /docs: protocol notes and pairing spec
