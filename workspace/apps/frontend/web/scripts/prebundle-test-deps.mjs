import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(__dirname, '..');
const cacheDir = join(webRoot, 'node_modules', '.cache', 'acme-test-deps');
const manifestPath = join(cacheDir, 'manifest.json');

const req = createRequire(join(webRoot, 'package.json'));
const viteReq = createRequire(req.resolve('vite'));
const esbuild = viteReq('esbuild');

const BUNDLE_SCHEMA_VERSION = 'v3';

function computeVersionHash() {
  const hash = createHash('sha256').update(BUNDLE_SCHEMA_VERSION);
  for (const pkg of ['antd', '@ant-design/icons', 'lucide-react', '@antv/x6']) {
    try {
      const pkgJsonPath = req.resolve(`${pkg}/package.json`);
      const { version } = JSON.parse(readFileSync(pkgJsonPath, 'utf8'));
      hash.update(`${pkg}@${version};`);
    } catch {
      hash.update(`${pkg}@missing;`);
    }
  }
  return hash.digest('hex').slice(0, 16);
}

export function getPrebundlePaths() {
  return {
    cacheDir,
    antd: join(cacheDir, 'antd.cjs'),
    antdIcons: join(cacheDir, 'antd-icons.cjs'),
    lucideReact: join(cacheDir, 'lucide-react.cjs'),
    antvX6: join(cacheDir, 'antv-x6.cjs'),
  };
}

export async function ensureTestPrebundles() {
  const paths = getPrebundlePaths();
  const expectedHash = computeVersionHash();
  if (
    existsSync(manifestPath) &&
    existsSync(paths.antd) &&
    existsSync(paths.antdIcons) &&
    existsSync(paths.lucideReact) &&
    existsSync(paths.antvX6)
  ) {
    try {
      const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
      if (manifest.hash === expectedHash) {
        return { built: false, paths };
      }
    } catch {
      // 重建损坏缓存
    }
  }

  mkdirSync(cacheDir, { recursive: true });
  const t0 = Date.now();

  // 1) @ant-design/icons（831 个图标静态 CJS 桩，Vite 外部直载免 ESM 转换，保留精确 .anticon.anticon-<kebab> 与 <svg data-icon>）
  const antdIconsDir = join(dirname(req.resolve('@ant-design/icons/package.json')), 'lib', 'icons');
  const antdIconNames = readdirSync(antdIconsDir)
    .filter((f) => f.endsWith('.js') && f !== 'index.js')
    .map((f) => f.slice(0, -3));
  const antdIconsSrc = [
    `'use strict';`,
    `const React = require('react');`,
    `function makeIcon(rawName) {`,
    `  const kebab = rawName.replace(/(Outlined|Filled|TwoTone)$/, '').replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();`,
    `  const Comp = React.forwardRef(function AntdIconStub(props, ref) {`,
    `    const { className, style, onClick, spin, rotate, twoToneColor, component, ...rest } = props || {};`,
    `    return React.createElement('span', {`,
    `      ...rest, ref, role: 'img', 'aria-label': kebab,`,
    `      className: 'anticon anticon-' + kebab + (className ? ' ' + className : ''), style, onClick,`,
    `    }, React.createElement('svg', { 'data-icon': kebab, 'aria-hidden': 'true' }));`,
    `  });`,
    `  Comp.displayName = rawName;`,
    `  return Comp;`,
    `}`,
    `exports.__esModule = true;`,
    `exports.createFromIconfontCN = () => makeIcon('iconfont');`,
    `exports.getTwoToneColor = () => '#1677ff';`,
    `exports.setTwoToneColor = () => {};`,
    `exports.default = makeIcon('icon');`,
    ...antdIconNames.map((n) => `exports.${n} = makeIcon('${n}');`),
  ].join('\n');
  writeFileSync(paths.antdIcons, antdIconsSrc, 'utf8');

  // 2) lucide-react（扫描 dist/cjs 导出名生成单文件 CJS 桩，Vite 外部直载免解析 300+ ESM 图标文件）
  const lucideCjs = req('lucide-react');
  const lucideIconNames = Object.keys(lucideCjs).filter(
    (k) => /^[A-Z]/.test(k) && k !== 'Icon' && k !== 'LucideIcon' && k !== 'default',
  );
  const lucideSrc = [
    `'use strict';`,
    `const React = require('react');`,
    `function makeLucide(rawName) {`,
    `  const kebab = rawName.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();`,
    `  const Comp = React.forwardRef(function LucideIconStub(props, ref) {`,
    `    const { size = 16, strokeWidth, absoluteStrokeWidth, color, className, style, ...rest } = props || {};`,
    `    return React.createElement('svg', {`,
    `      ...rest, ref, 'aria-hidden': 'true', width: size, height: size,`,
    `      'data-lucide': kebab, className: 'lucide lucide-' + kebab + (className ? ' ' + className : ''), style,`,
    `    });`,
    `  });`,
    `  Comp.displayName = rawName;`,
    `  return Comp;`,
    `}`,
    `exports.__esModule = true;`,
    `exports.createLucideIcon = (name) => makeLucide(name || 'Icon');`,
    `exports.Icon = makeLucide('Icon');`,
    `exports.icons = {};`,
    ...lucideIconNames.map((n) => `exports.${n} = makeLucide('${n}');`),
  ].join('\n');
  writeFileSync(paths.lucideReact, lucideSrc, 'utf8');

  // 3) 并发 esbuild 预打包 antd（内联 cssinjs noop + 内部图标桩 + 静默 AntD dev warning 刷屏）+ @antv/x6
  await Promise.all([
    esbuild.build({
      entryPoints: [req.resolve('antd')],
      bundle: true,
      platform: 'node',
      format: 'cjs',
      minifySyntax: true,
      define: { 'process.env.NODE_ENV': '"production"' },
      outfile: paths.antd,
      external: ['react', 'react-dom', 'react/jsx-runtime', 'react/jsx-dev-runtime', 'dayjs', 'dayjs/*'],
      plugins: [
        {
          name: 'acme-test-antd-fast',
          setup(build) {
            build.onLoad(
              { filter: /@ant-design[\\/]cssinjs[\\/](?:lib|es)[\\/]hooks[\\/]use(?:Style|CSSVar)Register\.js$/ },
              () => ({
                contents:
                  'exports.__esModule = true; exports.default = function() { return function(node) { return node; }; };',
                loader: 'js',
              }),
            );
            build.onResolve({ filter: /^@ant-design\/icons(\/.*)?$/ }, (args) => {
              if (args.path.includes('/components/Context')) return null;
              return { path: args.path, namespace: 'acme-antd-icon-stub' };
            });
            build.onLoad({ filter: /.*/, namespace: 'acme-antd-icon-stub' }, (args) => {
              const m = /([A-Z][A-Za-z0-9]+)$/.exec(args.path);
              const name = m ? m[1] : 'Icon';
              const kebab = name
                .replace(/(Outlined|Filled|TwoTone)$/, '')
                .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
                .toLowerCase();
              const contents = [
                `const React = require('react');`,
                `const C = React.forwardRef(function AntdIconStub(p, r) {`,
                `  const { className, style, onClick, spin, rotate, twoToneColor, component, ...rest } = p || {};`,
                `  return React.createElement('span', {`,
                `    ...rest, ref: r, role: 'img', 'aria-label': ${JSON.stringify(kebab)},`,
                `    className: ${JSON.stringify(`anticon anticon-${kebab}`)} + (className ? ' ' + className : ''),`,
                `    style, onClick,`,
                `  }, React.createElement('svg', { 'data-icon': ${JSON.stringify(kebab)}, 'aria-hidden': 'true' }));`,
                `});`,
                `C.displayName = ${JSON.stringify(name)};`,
                `exports.__esModule = true; exports.default = C;`,
              ].join('\n');
              return { contents, loader: 'js' };
            });
          },
        },
      ],
      logLevel: 'silent',
    }),
    esbuild.build({
      entryPoints: [req.resolve('@antv/x6')],
      bundle: true,
      platform: 'node',
      format: 'cjs',
      minifySyntax: true,
      outfile: paths.antvX6,
      external: ['react', 'react-dom'],
      logLevel: 'silent',
    }),
  ]);

  writeFileSync(manifestPath, JSON.stringify({ hash: expectedHash, builtAt: new Date().toISOString() }, null, 2));
  return { built: true, elapsedMs: Date.now() - t0, paths };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { performance } = await import('node:perf_hooks');
  const res = await ensureTestPrebundles();
  console.log('Prebundle result:', res.built ? `built in ${res.elapsedMs}ms` : 'cache hit');
  for (const [label, file] of [
    ['antd.cjs', res.paths.antd],
    ['antv-x6.cjs', res.paths.antvX6],
    ['antd-icons.cjs', res.paths.antdIcons],
    ['lucide-react.cjs', res.paths.lucideReact],
  ]) {
    const t0 = performance.now();
    await import(`file:///${file.replace(/\\/g, '/')}`);
    console.log(label.padEnd(20), (performance.now() - t0).toFixed(1), 'ms');
  }
}
