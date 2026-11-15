import { Menu, Tray, nativeImage } from 'electron';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// REQ-20261114-120111：系统托盘（AC-3）。「显示主窗」「退出」两动作起期；
// 图标为仓库内置最小占位（resources/tray.png），不引入未登记依赖。

const here = dirname(fileURLToPath(import.meta.url));
const trayIconPath = resolve(here, '../../resources/tray.png');

/**
 * 创建托盘。
 * @param {{ onShow?: () => void, onQuit?: () => void }} handlers
 * @returns {Tray}
 */
export function createTray({ onShow, onQuit } = {}) {
  const icon = nativeImage.createFromPath(trayIconPath);
  const tray = new Tray(icon);
  tray.setToolTip('ACME 多端前端交付台');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '显示主窗', click: () => onShow?.() },
    { type: 'separator' },
    { label: '退出', click: () => onQuit?.() },
  ]));
  tray.on('double-click', () => onShow?.());
  return tray;
}

/** 展示并聚焦主窗（托盘「显示主窗」动作；隐藏态恢复）。 */
export function showMainWindow(mainWindow) {
  if (!mainWindow) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}
