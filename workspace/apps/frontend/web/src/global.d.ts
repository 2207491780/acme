/// <reference types="vite/client" />

interface AcmeDesktopBridge {
  platform: string;
  versions: { electron: string; chrome: string };
  openExternal: (url: string) => Promise<boolean>;
  getInfo: () => Promise<{ platform: string; version?: string; versions: Record<string, string> }>;
  /** REQ-20261114-120111：检查更新（手动入口；结果态见 settings.updater.result）。 */
  checkUpdates: () => Promise<{
    status: 'disabled' | 'up-to-date' | 'ready' | 'unreachable';
    version?: string | null;
    error?: string;
  }>;
  /** REQ-20261114-120111：重启并安装已下载的更新。 */
  installUpdates: () => Promise<{ ok: boolean }>;
}

interface Window {
  acmeDesktop?: AcmeDesktopBridge;
}