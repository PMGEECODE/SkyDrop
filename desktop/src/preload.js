const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('skydrop', {
  getPathForFile: (file) => {
    try {
      if (webUtils && typeof webUtils.getPathForFile === 'function') {
        return webUtils.getPathForFile(file);
      }
    } catch (_) {}
    return file ? (file.path || '') : '';
  },
  getPeers: () => ipcRenderer.invoke('get-peers'),
  addManualPeer: (peerData) => ipcRenderer.invoke('add-manual-peer', peerData),
  getPairSession: () => ipcRenderer.invoke('get-pair-session'),
  getPairStatus: () => ipcRenderer.invoke('get-pair-status'),
  unpairPeer: (id) => ipcRenderer.invoke('unpair-peer', id),
  pairInitiate: (target) => ipcRenderer.invoke('pair-initiate', target),
  pairGetIncoming: () => ipcRenderer.invoke('pair-get-incoming'),
  pairAcceptIncoming: (reqId) => ipcRenderer.invoke('pair-accept-incoming', reqId),
  pairDeclineIncoming: (reqId) => ipcRenderer.invoke('pair-decline-incoming', reqId),
  pairConfirmSecret: (payload) => ipcRenderer.invoke('pair-confirm-secret', payload),
  getHistory: () => ipcRenderer.invoke('get-history'),
  clearHistory: () => ipcRenderer.invoke('clear-history'),
  deleteHistoryEntry: (id) => ipcRenderer.invoke('delete-history-entry', id),
  getFileThumbnail: (filePath) => ipcRenderer.invoke('get-file-thumbnail', filePath),
  getThumbnailCacheStats: () => ipcRenderer.invoke('get-thumbnail-cache-stats'),
  clearThumbnailCache: () => ipcRenderer.invoke('clear-thumbnail-cache'),
  deleteFileFromDisk: (filePath) => ipcRenderer.invoke('delete-file-from-disk', filePath),
  startDrag: (filePath) => ipcRenderer.send('start-drag', filePath),
  getSettings: () => ipcRenderer.invoke('get-settings'),
  updateSettings: (settings) => ipcRenderer.invoke('update-settings', settings),
  openPath: (targetPath) => ipcRenderer.invoke('open-path', targetPath),
  showInFolder: (targetPath) => ipcRenderer.invoke('show-in-folder', targetPath),
  openDownloadsFolder: () => ipcRenderer.invoke('open-downloads-folder'),
  selectFiles: () => ipcRenderer.invoke('select-files'),
  selectFolder: () => ipcRenderer.invoke('select-folder'),
  getAutostart: () => ipcRenderer.invoke('get-autostart'),
  setAutostart: (openAtLogin) => ipcRenderer.invoke('set-autostart', openAtLogin),
  getAppVersion: () => ipcRenderer.invoke('get-app-version'),
  checkForUpdates: () => ipcRenderer.invoke('check-for-updates'),
  getUpdateSettings: () => ipcRenderer.invoke('get-update-settings'),
  setUpdateSettings: (settings) => ipcRenderer.invoke('set-update-settings', settings),
  downloadAndInstallUpdate: () => ipcRenderer.invoke('download-and-install-update'),
  restartApp: () => ipcRenderer.invoke('restart-app'),
  onUpdateProgress: (callback) => {
    const subscription = (event, progress) => callback(progress);
    ipcRenderer.on('update-progress', subscription);
    return () => ipcRenderer.removeListener('update-progress', subscription);
  },
  onUpdateAvailable: (callback) => {
    const subscription = (event, update) => callback(update);
    ipcRenderer.on('update-available', subscription);
    return () => ipcRenderer.removeListener('update-available', subscription);
  },
  onShowUpdatingStatus: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('show-updating-status', subscription);
    return () => ipcRenderer.removeListener('show-updating-status', subscription);
  },
  sendFile: (filePath, targetUrl, authToken) =>
    ipcRenderer.invoke('send-file', { filePath, targetUrl, authToken }),
  minimizeWindow: () => ipcRenderer.invoke('window-minimize'),
  closeWindow: () => ipcRenderer.invoke('window-close'),
  retryPresence: (id) => ipcRenderer.invoke('retry-presence', id),
  getDiagnostics: () => ipcRenderer.invoke('get-diagnostics'),
  onPresenceUpdate: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('presence-update', subscription);
    return () => ipcRenderer.removeListener('presence-update', subscription);
  },
  onTransferProgress: (callback) => {
    const subscription = (event, progress) => callback(progress);
    ipcRenderer.on('transfer-progress', subscription);
    return () => ipcRenderer.removeListener('transfer-progress', subscription);
  },
  onReceiveProgress: (callback) => {
    const subscription = (event, progress) => callback(progress);
    ipcRenderer.on('receive-progress', subscription);
    return () => ipcRenderer.removeListener('receive-progress', subscription);
  },

  // ── Clipboard ──────────────────────────────────────────────────────────────
  clipboardList: () => ipcRenderer.invoke('clipboard-list'),
  clipboardAdd: (text) => ipcRenderer.invoke('clipboard-add', text),
  clipboardDelete: (id) => ipcRenderer.invoke('clipboard-delete', id),
  clipboardClear: (keepPinned) => ipcRenderer.invoke('clipboard-clear', keepPinned),
  clipboardPin: (id, pinned) => ipcRenderer.invoke('clipboard-pin', id, pinned),
  clipboardSyncIds: () => ipcRenderer.invoke('clipboard-sync-ids'),
  clipboardGetSettings: () => ipcRenderer.invoke('clipboard-get-settings'),
  clipboardSetSettings: (s) => ipcRenderer.invoke('clipboard-set-settings', s),
  onClipboardUpdate: (callback) => {
    const sub = (_, data) => callback(data);
    ipcRenderer.on('clipboard-update', sub);
    return () => ipcRenderer.removeListener('clipboard-update', sub);
  },

  // ── Hotspot ────────────────────────────────────────────────────────────────
  hotspotGetCapabilities: () => ipcRenderer.invoke('hotspot-get-capabilities'),
  hotspotStart: (opts) => ipcRenderer.invoke('hotspot-start', opts),
  hotspotStop: () => ipcRenderer.invoke('hotspot-stop'),
  hotspotGetStatus: () => ipcRenderer.invoke('hotspot-get-status')
});
