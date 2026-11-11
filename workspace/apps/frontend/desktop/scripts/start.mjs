import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const require = createRequire(import.meta.url);
const here = dirname(fileURLToPath(import.meta.url));
const desktopRoot = resolve(here, '..');
const webRoot = resolve(desktopRoot, '../web');
const webRequire = createRequire(resolve(webRoot, 'package.json'));

let electronPath;
try {
  electronPath = require('electron');
} catch {
  console.error('未找到 Electron 依赖。请先安装：pnpm --dir workspace/apps/frontend install');
  process.exit(1);
}

let viteBin;
try {
  const vitePkg = webRequire.resolve('vite/package.json');
  viteBin = resolve(dirname(vitePkg), JSON.parse(readFileSync(vitePkg, 'utf8')).bin.vite);
} catch {
  console.error('未找到 Web 依赖。请先安装：pnpm --dir workspace/apps/frontend install');
  process.exit(1);
}

console.log('构建 Web 资源（Vite）…');
const build = spawn(process.execPath, [viteBin, 'build'], { cwd: webRoot, stdio: 'inherit' });
const buildCode = await new Promise((res) => build.on('exit', res));
if (buildCode) {
  console.error('构建失败，已取消启动。');
  process.exit(buildCode);
}

console.log('启动 Electron（生产模式，加载 web/dist）…');
const electron = spawn(electronPath, [desktopRoot], {
  env: { ...process.env, WEB_DEV_SERVER_URL: '' },
  stdio: 'inherit',
});
electron.on('exit', (code) => process.exit(code ?? 0));