const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('skydrop', {
  getPeers: () => ipcRenderer.invoke('get-peers'),
  addManualPeer: (peerData) => ipcRenderer.invoke('add-manual-peer', peerData),
  getPairSession: () => ipcRenderer.invoke('get-pair-session'),
  getPairStatus: () => ipcRenderer.invoke('get-pair-status'),
  unpairPeer: (id) => ipcRenderer.invoke('unpair-peer', id),
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
  }
});
