import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, resolve } from 'node:path';

const require = createRequire(import.meta.url);
const here = dirname(fileURLToPath(import.meta.url));
const desktopRoot = resolve(here, '..');
const webRoot = resolve(desktopRoot, '../web');
const webRequire = createRequire(resolve(webRoot, 'package.json'));

/** 启动自有 Vite/Electron，清理完成后返回退出码；依赖可替换以避免测试启动窗口。 */
export async function startDev({
  createServer,
  electronPath,
  spawnElectron = spawn,
  env = process.env,
  signals = process,
  logger = console,
}) {
  let server;
  let electron;
  let electronClosed;
  let stopping = false;
  let finish;
  const stopped = new Promise((resolveStopped) => { finish = resolveStopped; });
  const stop = () => {
    stopping = true;
    finish({ code: 0 });
  };
  signals.on('SIGINT', stop);
  signals.on('SIGTERM', stop);

  try {
    server = await createServer({
      root: webRoot,
      server: { host: '127.0.0.1', port: Number(env.PORT || 5173), strictPort: true },
    });
    if (stopping) return 0;
    await server.listen();
    if (stopping) return 0;

    // 只信任已成功监听的自有 server，不能用任意 HTTP 响应判断启动成功。
    const devServerUrl = server.resolvedUrls?.local?.[0];
    if (!devServerUrl) throw new Error('Web 开发服务器未提供本地监听地址');
    logger.log(`启动 Electron，加载 ${devServerUrl}`);
    electron = spawnElectron(electronPath, [desktopRoot], {
      env: { ...env, WEB_DEV_SERVER_URL: devServerUrl },
      stdio: 'inherit',
    });
    electronClosed = new Promise((resolveClosed) => electron.once('close', resolveClosed));
    electron.once('error', (error) => finish({ error }));
    electron.once('exit', (code, signal) => finish({ code: code ?? (signal ? 1 : 0) }));

    const result = await stopped;
    if (result.error) throw result.error;
    return result.code;
  } finally {
    // 等待进行中的 createServer/listen 完成再清理，防止退出后又开始监听或启动窗口。
    if (electron?.pid && electron.exitCode === null && electron.signalCode === null && !electron.killed) {
      electron.kill();
    }
    try {
      await Promise.all([server?.close(), electronClosed]);
    } finally {
      signals.off('SIGINT', stop);
      signals.off('SIGTERM', stop);
    }
  }
}

async function main() {
  let electronPath;
  try {
    electronPath = require('electron');
  } catch {
    throw new Error('未找到 Electron 依赖。请先安装：pnpm --dir workspace/apps/frontend install');
  }

  // 直接导入 Vite ESM API，避免 require('vite') 的已弃用 CJS 入口。
  const viteEntry = resolve(dirname(webRequire.resolve('vite/package.json')), 'dist/node/index.js');
  const { createServer } = await import(pathToFileURL(viteEntry).href);
  process.exitCode = await startDev({ createServer, electronPath });
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error(`桌面开发启动失败：${error.message}`);
    process.exitCode = 1;
  });
}
