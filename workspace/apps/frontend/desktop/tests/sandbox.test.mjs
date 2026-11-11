import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

// 配置/受限环境回归，不替代真实 Electron 的沙箱集成验证。
test('should_keep_renderer_sandboxed_with_context_isolation_and_no_node_integration', () => {
  const main = readFileSync(new URL('../src/main/index.js', import.meta.url), 'utf8');
  const preferences = main.match(/webPreferences:\s*\{([\s\S]*?)\n\s*\}/)?.[1];
  assert.ok(preferences);
  assert.match(preferences, /\bsandbox:\s*true\b/);
  assert.match(preferences, /\bcontextIsolation:\s*true\b/);
  assert.match(preferences, /\bnodeIntegration:\s*false\b/);
  assert.match(main, /resolve\(here, '\.\.\/preload\/index\.cjs'\)/);
});

test('should_expose_preload_bridge_with_only_sandbox_supported_dependencies', async () => {
  const preload = readFileSync(new URL('../src/preload/index.cjs', import.meta.url), 'utf8');
  let exposed;
  const required = [];
  const invoked = [];
  runInNewContext(preload, {
    require(id) {
      required.push(id);
      assert.equal(id, 'electron');
      return {
        contextBridge: {
          exposeInMainWorld(name, api) {
            assert.equal(name, 'acmeDesktop');
            exposed = api;
          },
        },
        ipcRenderer: {
          async invoke(...args) { invoked.push(args); return { ok: true }; },
        },
      };
    },
    process: { platform: 'test-platform', versions: { electron: '37.0.0', chrome: 'test-chrome' } },
  });
  assert.deepEqual(required, ['electron']);
  assert.equal(exposed.platform, 'test-platform');
  assert.equal(exposed.versions.electron, '37.0.0');
  assert.equal(exposed.versions.chrome, 'test-chrome');
  assert.deepEqual(await exposed.getInfo(), { ok: true });
  assert.deepEqual(await exposed.openExternal('https://example.com'), { ok: true });
  assert.deepEqual(invoked, [['acme:get-info'], ['acme:open-external', 'https://example.com']]);
});
