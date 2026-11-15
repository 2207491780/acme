import { autoUpdater } from 'electron-updater';
import { BrowserWindow, dialog } from 'electron';

// REQ-20261114-120111：基于 electron-updater 的更新服务（AC-1/AC-2/AC-5）。
// - 自动检查仅在 autoCheckEnabled=true（已打包且配置了更新源）时启动（AC-5）；
// - autoDownload=false：检查发现新版本后自动续下载，下载完成提示用户重启安装（AC-1）；
// - 无新版本/更新源不可达：手动检查明确提示，后台检查静默，全程不阻塞主窗（AC-2）。

let feedConfigured = false;

function ensureFeed(updateUrl) {
  if (feedConfigured) return;
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = false;
  autoUpdater.allowDowngrade = false;
  autoUpdater.setFeedURL({ provider: 'generic', url: updateUrl });
  feedConfigured = true;
}

/** 单次检查：以事件驱动收敛为固定结果态，任何异常都不向外抛（AC-2 不阻塞主窗）。 */
function checkOnce() {
  return new Promise((resolve) => {
    const listeners = [];
    const finish = (result) => {
      for (const [name, fn] of listeners) autoUpdater.removeListener(name, fn);
      resolve(result);
    };
    const onAvailable = (info) => finish({ status: 'available', version: info?.version ?? null });
    const onNotAvailable = (info) => finish({ status: 'up-to-date', version: info?.version ?? null });
    const onError = (err) => finish({ status: 'unreachable', error: String(err?.message ?? err) });
    listeners.push(
      ['update-available', onAvailable],
      ['update-not-available', onNotAvailable],
      ['error', onError],
    );
    for (const [name, fn] of listeners) autoUpdater.on(name, fn);
    autoUpdater.checkForUpdates().catch(onError);
  });
}

/**
 * 手动检查入口（设置页「检查更新」）。
 * @param {string | null} updateUrl
 * @returns {Promise<{ status: 'disabled' | 'up-to-date' | 'ready' | 'unreachable', version?: string | null, error?: string }>}
 */
async function checkForUpdates(updateUrl) {
  if (!updateUrl) return { status: 'disabled' };
  ensureFeed(updateUrl);
  const found = await checkOnce();
  if (found.status !== 'available') return found;
  try {
    await autoUpdater.downloadUpdate();
    return { status: 'ready', version: found.version };
  } catch (err) {
    return { status: 'unreachable', error: String(err?.message ?? err) };
  }
}

/** 重启并安装已下载的更新（AC-1 末段）。 */
function installUpdates() {
  autoUpdater.quitAndInstall();
}

function defaultNotify({ title, detail }) {
  const win = BrowserWindow.getAllWindows()[0] ?? null;
  if (win) dialog.showMessageBox(win, { type: 'info', title, message: title, detail, buttons: ['OK'] });
}

/**
 * 创建更新服务。
 * @param {{ updateUrl: string | null, autoCheckEnabled: boolean, notify?: (n: { title: string, detail: string }) => void }} options
 */
export function createUpdaterService({ updateUrl, autoCheckEnabled, notify = defaultNotify }) {
  return {
    /** 设置页手动检查（IPC acme:check-updates 直通）。 */
    checkForUpdates: () => checkForUpdates(updateUrl),
    /** 安装已下载更新（IPC acme:install-updates 直通）。 */
    installUpdates,
    /** 启动后台检查：静默，仅下载完成后提示重启安装（AC-1/AC-2/AC-5）。 */
    startBackgroundCheck() {
      if (!autoCheckEnabled) return;
      checkForUpdates(updateUrl)
        .then((result) => {
          if (result.status === 'ready') {
            notify({
              title: '发现新版本',
              detail: `新版本 ${result.version ?? ''} 已下载完成，重启应用即可安装。`,
            });
          }
        })
        .catch(() => {});
    },
  };
}
