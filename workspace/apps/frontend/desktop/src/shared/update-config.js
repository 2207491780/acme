// REQ-20261114-120111：更新与关窗行为配置（纯函数，供 node:test 单测，不依赖 Electron）。
// 更新源经环境变量注入；dev（未打包）禁用自动检查（AC-5）。

/**
 * 解析自动更新配置。
 * @param {{ env?: Record<string, string | undefined>, isPackaged?: boolean }} input
 * @returns {{ updateUrl: string | null, autoCheckEnabled: boolean }}
 */
export function parseUpdateConfig({ env = {}, isPackaged = false } = {}) {
  const raw = env?.ACME_DESKTOP_UPDATE_URL ?? '';
  const updateUrl = raw.trim() ? raw.trim() : null;
  return {
    updateUrl,
    autoCheckEnabled: Boolean(updateUrl) && isPackaged === true,
  };
}

/**
 * 关窗行为：默认直接退出；配置开启（ACME_DESKTOP_CLOSE_TO_TRAY=1）时最小化到托盘。
 * @param {unknown} closeToTray
 * @returns {'quit' | 'minimize-to-tray'}
 */
export function resolveCloseAction(closeToTray) {
  return closeToTray ? 'minimize-to-tray' : 'quit';
}
