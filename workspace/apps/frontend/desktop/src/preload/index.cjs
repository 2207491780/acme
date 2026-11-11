const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('acmeDesktop', {
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
  },
  openExternal: (url) => ipcRenderer.invoke('acme:open-external', url),
  getInfo: () => ipcRenderer.invoke('acme:get-info'),
});