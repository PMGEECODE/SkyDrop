const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('updatePopup', {
  onInitUpdateData: (callback) => {
    const handler = (_event, data) => callback(data);
    ipcRenderer.on('init-update-data', handler);
    return () => ipcRenderer.removeListener('init-update-data', handler);
  },
  onDownloadProgress: (callback) => {
    const handler = (_event, progress) => callback(progress);
    ipcRenderer.on('download-progress', handler);
    return () => ipcRenderer.removeListener('download-progress', handler);
  },
  startUpdate: () => ipcRenderer.invoke('start-update-from-popup'),
  dismissPopup: () => ipcRenderer.invoke('dismiss-update-popup')
});
