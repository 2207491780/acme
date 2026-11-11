/// <reference types="vite/client" />

interface AcmeDesktopBridge {
  platform: string;
  versions: { electron: string; chrome: string };
  openExternal: (url: string) => Promise<boolean>;
  getInfo: () => Promise<{ platform: string; versions: Record<string, string> }>;
}

interface Window {
  acmeDesktop?: AcmeDesktopBridge;
}