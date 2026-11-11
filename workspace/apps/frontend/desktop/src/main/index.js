import { app, BrowserWindow, shell, ipcMain, Menu, protocol } from 'electron';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { isSafeExternalUrl } from '../shared/security.js';
import { APP_ORIGIN, schemePrivileges, registerAppProtocol } from '../shared/app-protocol.js';

const here = dirname(fileURLToPath(import.meta.url));
const preloadPath = resolve(here, '../preload/index.cjs');
const distRoot = resolve(here, '../../../web/dist');
const devServerUrl = process.env.WEB_DEV_SERVER_URL || '';

protocol.registerSchemesAsPrivileged(schemePrivileges);

let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#060913',
    title: 'ACME 多端前端交付台',
    autoHideMenuBar: true,
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isSafeExternalUrl(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.webContents.on('will-navigate', (event, url) => {
    // 精确匹配 origin：startsWith('acme://app') 会放行 acme://apple 等同前缀的不同 origin。
    const allowed = devServerUrl
      ? url.startsWith(devServerUrl)
      : url === APP_ORIGIN || url.startsWith(`${APP_ORIGIN}/`);
    if (!allowed) {
      event.preventDefault();
      if (isSafeExternalUrl(url)) shell.openExternal(url);
    }
  });

  if (devServerUrl) mainWindow.loadURL(devServerUrl);
  else mainWindow.loadURL(`${APP_ORIGIN}/index.html`);

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

ipcMain.handle('acme:get-info', () => ({
  platform: process.platform,
  packaged: app.isPackaged,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
}));

ipcMain.handle('acme:open-external', (_event, url) => {
  if (!isSafeExternalUrl(url)) return { ok: false, error: 'unsafe-url' };
  shell.openExternal(url);
  return { ok: true };
});

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    if (!devServerUrl) registerAppProtocol(distRoot);
    createWindow();
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}