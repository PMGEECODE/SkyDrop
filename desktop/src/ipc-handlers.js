let electron = null;
try {
  electron = require('electron');
} catch (_) {}
const { ipcMain, dialog, shell, app, nativeImage } = electron || {};
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const agent = new https.Agent({ rejectUnauthorized: false });
const thumbnailCache = new Map();

async function getThumbnail(filePath) {
  if (!filePath || !fs.existsSync(filePath)) return null;
  if (thumbnailCache.has(filePath)) {
    return thumbnailCache.get(filePath);
  }
  const ext = path.extname(filePath).toLowerCase();
  const visualExts = ['.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.mp4', '.mkv', '.mov', '.avi'];
  if (!visualExts.includes(ext)) {
    return null;
  }

  try {
    if (nativeImage && typeof nativeImage.createThumbnailFromPath === 'function') {
      const img = await nativeImage.createThumbnailFromPath(filePath, { width: 120, height: 120 });
      if (img && !img.isEmpty()) {
        const dataUrl = img.toDataURL();
        if (thumbnailCache.size > 200) {
          const firstKey = thumbnailCache.keys().next().value;
          thumbnailCache.delete(firstKey);
        }
        thumbnailCache.set(filePath, dataUrl);
        return dataUrl;
      }
    }
  } catch (_) {}
  return null;
}

/**
 * Helper to make JSON HTTP/HTTPS requests to the local Go daemon.
 */
function requestDaemon(url, method = 'GET', body = null, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const isHttps = url.startsWith('https');
    const client = isHttps ? https : http;
    const urlObj = new URL(url);

    const headers = {
      Accept: 'application/json'
    };

    let bodyData = null;
    if (body) {
      bodyData = typeof body === 'string' ? body : JSON.stringify(body);
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(bodyData);
    }

    const req = client.request(
      urlObj,
      {
        method,
        headers,
        agent: isHttps ? agent : undefined,
        timeout: timeoutMs
      },
      (res) => {
        let raw = '';
        res.on('data', (chunk) => (raw += chunk));
        res.on('end', () => {
          try {
            const parsed = raw ? JSON.parse(raw) : {};
            if (res.statusCode >= 200 && res.statusCode < 300) {
              resolve({ ok: true, status: res.statusCode, data: parsed });
            } else {
              resolve({ ok: false, status: res.statusCode, error: parsed.error || parsed.message || raw });
            }
          } catch (e) {
            resolve({ ok: false, status: res.statusCode, error: raw || e.message });
          }
        });
      }
    );

    req.on('error', (err) => resolve({ ok: false, status: 0, error: err.message }));
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, status: 0, error: 'Request timeout' });
    });

    if (bodyData) {
      req.write(bodyData);
    }
    req.end();
  });
}

/**
 * Streams a local file to the daemon upload endpoint with live progress.
 */
function uploadFileToDaemon(daemonBaseUrl, targetUrl, authToken, filePath, onProgress) {
  return new Promise((resolve) => {
    if (!fs.existsSync(filePath)) {
      return resolve({ ok: false, error: 'File does not exist' });
    }

    const stat = fs.statSync(filePath);
    const fileSize = stat.size;
    const filename = path.basename(filePath);
    const boundary = '----SkyDropBoundary' + Math.random().toString(36).substring(2);

    const partHeaderTarget = `--${boundary}\r\nContent-Disposition: form-data; name="target_url"\r\n\r\n${targetUrl}\r\n`;
    const partHeaderAuth = authToken
      ? `--${boundary}\r\nContent-Disposition: form-data; name="auth_token"\r\n\r\n${authToken}\r\n`
      : '';
    const partHeaderFile = `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${filename}"\r\nContent-Type: application/octet-stream\r\n\r\n`;
    const partFooter = `\r\n--${boundary}--\r\n`;

    const totalPayloadBytes =
      Buffer.byteLength(partHeaderTarget) +
      Buffer.byteLength(partHeaderAuth) +
      Buffer.byteLength(partHeaderFile) +
      fileSize +
      Buffer.byteLength(partFooter);

    const isHttps = daemonBaseUrl.startsWith('https');
    const client = isHttps ? https : http;
    const urlObj = new URL(`${daemonBaseUrl}/api/send`);

    const req = client.request(
      urlObj,
      {
        method: 'POST',
        agent: isHttps ? agent : undefined,
        headers: {
          'Content-Type': `multipart/form-data; boundary=${boundary}`,
          'Content-Length': totalPayloadBytes
        },
        timeout: 30 * 60 * 1000 // 30 min for large file drops
      },
      (res) => {
        let raw = '';
        res.on('data', (c) => (raw += c));
        res.on('end', () => {
          try {
            const data = JSON.parse(raw);
            if (res.statusCode >= 200 && res.statusCode < 300) {
              resolve({ ok: true, data });
            } else {
              const friendlyMessage = data.message || data.error || `Upload failed with HTTP ${res.statusCode}`;
              resolve({
                ok: false,
                error: friendlyMessage,
                statusCode: data.status_code || res.statusCode,
                category: data.category || 'unknown',
                details: data.details || data.message || raw,
                targetUrl: data.target_url || targetUrl
              });
            }
          } catch (e) {
            resolve({
              ok: false,
              error: `Upload failed (HTTP ${res.statusCode})`,
              statusCode: res.statusCode,
              category: 'network',
              details: raw || e.message,
              targetUrl
            });
          }
        });
      }
    );

    req.on('error', (err) => resolve({
      ok: false,
      error: `Connection error: ${err.message}`,
      category: 'network',
      details: err.stack || err.message,
      targetUrl
    }));

    // Send metadata headers
    req.write(partHeaderTarget);
    if (partHeaderAuth) req.write(partHeaderAuth);
    req.write(partHeaderFile);

    // Stream file content
    let uploadedBytes = 0;
    const stream = fs.createReadStream(filePath, { highWaterMark: 64 * 1024 });

    stream.on('data', (chunk) => {
      uploadedBytes += chunk.length;
      if (onProgress && fileSize > 0) {
        const percent = Math.min(100, Math.round((uploadedBytes / fileSize) * 100));
        onProgress({ filename, uploadedBytes, totalBytes: fileSize, percent });
      }
      req.write(chunk);
    });

    stream.on('end', () => {
      req.write(partFooter);
      req.end();
    });

    stream.on('error', (err) => {
      req.destroy();
      resolve({ ok: false, error: err.message });
    });
  });
}

function registerIpcHandlers(mainWindow, daemonManager, thumbnailManager, autostartManager, updater) {
  const getBase = () => daemonManager.getBaseUrl();

  if (!autostartManager) {
    const AutostartManager = require('./autostart-manager');
    autostartManager = new AutostartManager({ app });
  }
  if (!updater) {
    const { Updater } = require('./updater');
    updater = new Updater({ app });
  }

  ipcMain.handle('get-peers', async () => {
    return requestDaemon(`${getBase()}/api/peers?scan=true`);
  });

  ipcMain.handle('add-manual-peer', async (event, peerData) => {
    return requestDaemon(`${getBase()}/api/peers/add`, 'POST', peerData);
  });

  ipcMain.handle('get-pair-session', async () => {
    return requestDaemon(`${getBase()}/api/pair/new`, 'POST');
  });

  ipcMain.handle('get-pair-status', async () => {
    return requestDaemon(`${getBase()}/api/pair/status`);
  });

  ipcMain.handle('unpair-peer', async (event, id) => {
    return requestDaemon(`${getBase()}/api/pair/unpair`, 'POST', { id });
  });

  ipcMain.handle('pair-initiate', async (_event, target) => {
    return requestDaemon(`${getBase()}/api/pair/initiate`, 'POST', target || {}, 50000);
  });

  ipcMain.handle('pair-get-incoming', async () => {
    return requestDaemon(`${getBase()}/api/pair/incoming`);
  });

  ipcMain.handle('pair-accept-incoming', async (_event, reqId) => {
    return requestDaemon(`${getBase()}/api/pair/incoming/accept`, 'POST', { request_id: reqId });
  });

  ipcMain.handle('pair-decline-incoming', async (_event, reqId) => {
    return requestDaemon(`${getBase()}/api/pair/incoming/decline`, 'POST', { request_id: reqId });
  });

  ipcMain.handle('pair-confirm-secret', async (_event, payload) => {
    return requestDaemon(`${getBase()}/api/pair/confirm`, 'POST', payload || {});
  });

  ipcMain.handle('get-history', async () => {
    return requestDaemon(`${getBase()}/api/history`);
  });

  ipcMain.handle('clear-history', async () => {
    return requestDaemon(`${getBase()}/api/history/clear`, 'POST');
  });

  ipcMain.handle('delete-history-entry', async (event, id) => {
    return requestDaemon(`${getBase()}/api/history/delete`, 'POST', { id });
  });

  ipcMain.handle('get-file-thumbnail', async (event, filePath) => {
    if (thumbnailManager) {
      return thumbnailManager.getThumbnail(filePath);
    }
    return getThumbnail(filePath);
  });

  ipcMain.handle('get-thumbnail-cache-stats', async () => {
    if (thumbnailManager) {
      return thumbnailManager.getCacheStats();
    }
    return { count: 0, totalSizeBytes: 0 };
  });

  ipcMain.handle('clear-thumbnail-cache', async () => {
    if (thumbnailManager) {
      return thumbnailManager.clearCache();
    }
    return { ok: true };
  });

  ipcMain.handle('delete-file-from-disk', async (event, filePath) => {
    if (!filePath) return { ok: false, error: 'Path empty' };
    try {
      if (fs.existsSync(filePath)) {
        await fs.promises.unlink(filePath);
      }
      return { ok: true };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  });

  ipcMain.on('start-drag', (event, filePath) => {
    if (!filePath || !fs.existsSync(filePath)) return;
    const iconPath = path.join(__dirname, '../assets/icon.png');
    event.sender.startDrag({
      file: filePath,
      icon: iconPath
    });
  });

  ipcMain.handle('get-settings', async () => {
    return requestDaemon(`${getBase()}/api/settings`);
  });

  ipcMain.handle('update-settings', async (event, settings) => {
    return requestDaemon(`${getBase()}/api/settings`, 'POST', settings);
  });

  ipcMain.handle('open-path', async (event, targetPath) => {
    if (!targetPath) return { ok: false, error: 'Path empty' };
    const err = await shell.openPath(targetPath);
    return { ok: !err, error: err };
  });

  ipcMain.handle('show-in-folder', async (event, targetPath) => {
    if (!targetPath) return { ok: false, error: 'Path empty' };
    shell.showItemInFolder(targetPath);
    return { ok: true };
  });

  ipcMain.handle('open-downloads-folder', async () => {
    return requestDaemon(`${getBase()}/api/folder/open`, 'POST');
  });

  ipcMain.handle('select-files', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Select Files to Send',
      properties: ['openFile', 'multiSelections']
    });
    if (result.canceled) return [];
    return result.filePaths.map((fp) => ({
      path: fp,
      name: path.basename(fp),
      size: fs.existsSync(fp) ? fs.statSync(fp).size : 0
    }));
  });

  ipcMain.handle('select-folder', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Select Destination Folder',
      properties: ['openDirectory', 'createDirectory']
    });
    if (result.canceled || !result.filePaths.length) return null;
    return result.filePaths[0];
  });

  ipcMain.handle('get-autostart', () => {
    return {
      enabled: autostartManager.isEnabled(),
      preference: autostartManager.getPreference()
    };
  });

  ipcMain.handle('set-autostart', (event, openAtLogin) => {
    if (openAtLogin) {
      return autostartManager.enable();
    } else {
      return autostartManager.disable();
    }
  });

  ipcMain.handle('get-app-version', () => {
    return (app && typeof app.getVersion === 'function') ? app.getVersion() : require('../package.json').version;
  });

  ipcMain.handle('check-for-updates', async () => {
    return updater.checkGitHubUpdate();
  });

  ipcMain.handle('get-update-settings', () => {
    return {
      autoCheck: updater.isAutoCheckEnabled(),
      lastCheckTime: updater.getLastCheckTime()
    };
  });

  ipcMain.handle('set-update-settings', (event, settings) => {
    if (settings && typeof settings.autoCheck === 'boolean') {
      updater.setAutoCheckEnabled(settings.autoCheck);
    }
    return { ok: true };
  });

  ipcMain.handle('download-and-install-update', async () => {
    return updater.downloadAndInstall((progress) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('update-progress', progress);
      }
    });
  });

  ipcMain.handle('restart-app', () => {
    updater.restartApp();
  });

  ipcMain.handle('send-file', async (event, { filePath, targetUrl, authToken }) => {
    return uploadFileToDaemon(getBase(), targetUrl, authToken, filePath, (prog) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('transfer-progress', prog);
      }
    });
  });

  ipcMain.handle('window-minimize', () => {
    if (mainWindow) mainWindow.minimize();
  });

  ipcMain.handle('window-close', () => {
    if (mainWindow) mainWindow.hide();
  });

  ipcMain.handle('retry-presence', async (event, peerId) => {
    return requestDaemon(`${getBase()}/api/presence/retry`, 'POST', peerId ? { id: peerId } : {});
  });

  ipcMain.handle('get-diagnostics', async () => {
    return requestDaemon(`${getBase()}/api/diagnostics`);
  });

  // Subscribe to real-time presence SSE events
  let sseReq = null;
  function connectSSE() {
    if (sseReq) {
      try { sseReq.destroy(); } catch (_) {}
      sseReq = null;
    }
    const base = getBase();
    if (!base) {
      setTimeout(connectSSE, 2000);
      return;
    }
    const isHttps = base.startsWith('https');
    const client = isHttps ? https : http;
    try {
      const urlObj = new URL(`${base}/api/presence/events`);
      sseReq = client.get(urlObj, { agent: isHttps ? agent : undefined }, (res) => {
        let buffer = '';
        res.on('data', (chunk) => {
          buffer += chunk.toString();
          const lines = buffer.split('\n\n');
          buffer = lines.pop();
          for (const block of lines) {
            for (const line of block.split('\n')) {
              if (line.startsWith('data: ')) {
                try {
                  const eventData = JSON.parse(line.substring(6));
                  if (mainWindow && !mainWindow.isDestroyed()) {
                    if (eventData.type === 'receive_progress') {
                      mainWindow.webContents.send('receive-progress', eventData);
                    } else {
                      mainWindow.webContents.send('presence-update', eventData);
                    }
                    if (eventData.type === 'pair_request') {
                      if (mainWindow.isMinimized()) mainWindow.restore();
                      mainWindow.show();
                      mainWindow.focus();
                    }
                  }
                } catch (_) {}
              }
            }
          }
        });
        res.on('end', () => setTimeout(connectSSE, 2000));
      });
      sseReq.on('error', () => setTimeout(connectSSE, 3000));
    } catch (_) {
      setTimeout(connectSSE, 3000);
    }
  }

  setTimeout(connectSSE, 1500);

  // ── Clipboard IPC ──────────────────────────────────────────────────────────

  ipcMain.handle('clipboard-list', async () => {
    return requestDaemon(`${getBase()}/clipboard/list`);
  });

  ipcMain.handle('clipboard-add', async (_event, text) => {
    if (typeof text !== 'string' || text.length === 0) {
      return { ok: false, error: 'Text must be a non-empty string' };
    }
    if (Buffer.byteLength(text, 'utf8') > 65536) {
      return { ok: false, error: 'Text exceeds 64 KB limit' };
    }
    return requestDaemon(`${getBase()}/clipboard/add`, 'POST', { text });
  });

  ipcMain.handle('clipboard-delete', async (_event, id) => {
    if (typeof id !== 'string' || id.length === 0) {
      return { ok: false, error: 'ID must be a non-empty string' };
    }
    return requestDaemon(`${getBase()}/clipboard/delete`, 'POST', { id });
  });

  ipcMain.handle('clipboard-clear', async (_event, keepPinned) => {
    return requestDaemon(`${getBase()}/clipboard/clear`, 'POST', {
      keep_pinned: keepPinned !== false
    });
  });

  ipcMain.handle('clipboard-pin', async (_event, id, pinned) => {
    if (typeof id !== 'string' || id.length === 0) {
      return { ok: false, error: 'ID must be a non-empty string' };
    }
    return requestDaemon(`${getBase()}/clipboard/pin`, 'POST', {
      id,
      pinned: Boolean(pinned)
    });
  });

  ipcMain.handle('clipboard-sync-ids', async () => {
    return requestDaemon(`${getBase()}/clipboard/sync`);
  });

  ipcMain.handle('clipboard-get-settings', async () => {
    return requestDaemon(`${getBase()}/clipboard/settings`);
  });

  ipcMain.handle('clipboard-set-settings', async (_event, settings) => {
    if (!settings || typeof settings !== 'object') {
      return { ok: false, error: 'Settings must be an object' };
    }
    return requestDaemon(`${getBase()}/clipboard/settings`, 'POST', settings);
  });

  // ── Hotspot IPC ──────────────────────────────────────────────────────────

  ipcMain.handle('hotspot-get-capabilities', async () => {
    return requestDaemon(`${getBase()}/api/hotspot/capabilities`);
  });

  ipcMain.handle('hotspot-start', async (_event, opts) => {
    return requestDaemon(`${getBase()}/api/hotspot/start`, 'POST', opts || {});
  });

  ipcMain.handle('hotspot-stop', async () => {
    return requestDaemon(`${getBase()}/api/hotspot/stop`, 'POST');
  });

  ipcMain.handle('hotspot-get-status', async () => {
    return requestDaemon(`${getBase()}/api/hotspot/status`);
  });
}

module.exports = { registerIpcHandlers };
