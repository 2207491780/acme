// 生成跨端设计令牌校验页：从 @acme/core 源码取唯一事实源令牌，注入静态模板。
// 运行：pnpm ui:guide（无需先 build core；用 vite 自带的 esbuild 即时打包 core 源码）。
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const require = createRequire(import.meta.url);

function loadEsbuild() {
  const candidates = [path.join(root, 'node_modules/.pnpm/node_modules')];
  try {
    const viteMain = require.resolve('vite', { paths: [path.join(root, 'web')] });
    candidates.unshift(path.dirname(viteMain));
  } catch {
    /* vite 不可解析时退回 hoist 目录 */
  }
  for (const p of candidates) {
    try {
      return require(require.resolve('esbuild', { paths: [p] }));
    } catch {
      /* try next */
    }
  }
  throw new Error('找不到 esbuild（应随 web 的 vite 安装）。请先在 frontend 执行 pnpm install。');
}

const esbuild = loadEsbuild();
const bundled = await esbuild.build({
  entryPoints: [path.join(root, 'packages/core/src/index.ts')],
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node20',
  write: false,
  logLevel: 'silent',
});
const code = bundled.outputFiles[0].text;
const core = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'));

const STATUS_ORDER = ['draft', 'building', 'testing', 'approved', 'published', 'failed', 'cancelled'];

const gradients = {};
for (const a of core.ACCENT_ORDER) {
  gradients[a] = {
    dark: { brand: core.GRADIENTS.brand(a), hero: core.GRADIENTS.hero(a, 'dark'), text: core.GRADIENTS.text(a) },
    light: { brand: core.GRADIENTS.brand(a), hero: core.GRADIENTS.hero(a, 'light'), text: core.GRADIENTS.text(a) },
  };
}

const TOKENS = {
  generatedAt: new Date().toISOString(),
  accents: core.ACCENTS,
  accentOrder: core.ACCENT_ORDER,
  onAccent: core.ON_ACCENT,
  companions: core.ACCENT_COMPANIONS,
  brandHues: core.BRAND_HUES,
  tones: core.TONES,
  toneAntd: core.TONE_ANTD,
  platformColors: core.PLATFORM_COLORS,
  platformAntd: core.PLATFORM_ANTD,
  envColors: core.ENV_COLORS,
  envAntd: core.ENV_ANTD,
  healthColors: core.HEALTH_COLORS,
  healthAntd: core.HEALTH_ANTD,
  healthLabels: core.HEALTH_LABELS,
  dark: core.DARK_NEUTRALS,
  light: core.LIGHT_NEUTRALS,
  spacing: core.SPACING,
  radii: core.RADII,
  fontFamily: core.FONT_FAMILY,
  fontSize: core.FONT_SIZE,
  fontWeight: core.FONT_WEIGHT,
  lineHeight: core.LINE_HEIGHT,
  letterSpacing: core.LETTER_SPACING,
  motion: core.MOTION,
  shadows: core.SHADOWS,
  z: core.Z,
  density: core.DENSITY,
  gradients,
  statusOrder: STATUS_ORDER,
  statusTone: Object.fromEntries(STATUS_ORDER.map((s) => [s, core.statusTone(s)])),
  statusLabel: Object.fromEntries(STATUS_ORDER.map((s) => [s, core.statusLabel(s)])),
  statusColor: Object.fromEntries(STATUS_ORDER.map((s) => [s, core.statusColor(s)])),
  platformShort: Object.fromEntries(['web', 'desktop', 'mobile'].map((p) => [p, core.platformShort(p)])),
  envLabel: Object.fromEntries(['dev', 'test', 'prod'].map((e) => [e, core.envLabel(e)])),
};

const template = fs.readFileSync(path.join(here, 'style-guide.template.html'), 'utf8');
const html = template.replace('/*__TOKENS__*/', 'const T = ' + JSON.stringify(TOKENS, null, 2) + ';');

const outDir = path.join(root, 'ui-spec');
fs.mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, 'style-guide.html');
fs.writeFileSync(outFile, html, 'utf8');
console.log('✓ 校验页已生成:', path.relative(root, outFile), '(' + (html.length / 1024).toFixed(1) + ' KB)');