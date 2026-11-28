import { app, BrowserWindow, shell, ipcMain, Menu, protocol } from 'electron';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { isSafeExternalUrl } from '../shared/security.js';
import { APP_ORIGIN, schemePrivileges, registerAppProtocol } from '../shared/app-protocol.js';
import { parseUpdateConfig, resolveCloseAction } from '../shared/update-config.js';
import { createUpdaterService } from './updater.js';
import { createTray, showMainWindow } from './tray.js';

const here = dirname(fileURLToPath(import.meta.url));
const preloadPath = resolve(here, '../preload/index.cjs');
const distRoot = resolve(here, '../../../web/dist');
const devServerUrl = process.env.WEB_DEV_SERVER_URL || '';

/** URL origin 提取（P3-05）：畸形 URL 返回 null（调用方按拒绝处理）。 */
function safeOrigin(u) {
  try {
    return new URL(u).origin;
  } catch {
    return null;
  }
}

// REQ-20261114-120111：更新与托盘配置（dev 未打包自动禁用自动检查，AC-5）。
const updateConfig = parseUpdateConfig({ env: process.env, isPackaged: app.isPackaged });
const closeAction = resolveCloseAction(process.env.ACME_DESKTOP_CLOSE_TO_TRAY);
const updater = createUpdaterService(updateConfig);
let tray = null;
let quitting = false;

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
    // dev 分支同样按 origin 相等判定（P3-05）：devServerUrl 前缀匹配可被
    // http://localhost:5173.evil.com 这类同前缀异源绕过；畸形 URL 一律拒绝。
    const allowed = devServerUrl
      ? safeOrigin(url) !== null && safeOrigin(url) === safeOrigin(devServerUrl)
      : url === APP_ORIGIN || url.startsWith(`${APP_ORIGIN}/`);
    if (!allowed) {
      event.preventDefault();
      if (isSafeExternalUrl(url)) shell.openExternal(url);
    }
  });

  if (devServerUrl) {
    mainWindow.loadURL(devServerUrl);
  } else {
    // Windows 竞态加固：构建刚完成时首载偶发 acme:// ERR_FILE_NOT_FOUND（文件系统索引延迟），
    // 失败后短暂等待重试一次（REQ-20261119-063129 后 S13 冒烟实证两连发）。
    mainWindow.loadURL(`${APP_ORIGIN}/index.html`).catch(() => {
      setTimeout(() => mainWindow.loadURL(`${APP_ORIGIN}/index.html`).catch(() => {}), 300);
    });
  }

  // AC-3：配置开启（ACME_DESKTOP_CLOSE_TO_TRAY=1）时关窗最小化到托盘；托盘「退出」/before-quit 正常退出。
  mainWindow.on('close', (event) => {
    if (!quitting && closeAction === 'minimize-to-tray') {
      event.preventDefault();
      mainWindow.hide();
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

ipcMain.handle('acme:get-info', () => ({
  platform: process.platform,
  packaged: app.isPackaged,
  version: app.getVersion(),
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

// REQ-20261114-120111：IPC 扩展仅两个通道（最小暴露面）。
ipcMain.handle('acme:check-updates', () => updater.checkForUpdates());
ipcMain.handle('acme:install-updates', () => {
  updater.installUpdates();
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
    tray = createTray({
      onShow: () => showMainWindow(mainWindow),
      onQuit: () => {
        quitting = true;
        app.quit();
      },
    });
    updater.startBackgroundCheck();
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('before-quit', () => {
    quitting = true;
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}