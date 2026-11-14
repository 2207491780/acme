/**
 * REQ-20261114-120103 AC-8：.env.development/.env.production 分层——`pnpm dev`/`build` 下
 * WEB_API_TARGET 既有语义不变（缺省 localhost:9888）。
 *
 * 断言形态（委派单 AC-8「构建配置断言或环境注入断言」）：构建配置断言——直接读取分层文件与
 * vite.config.ts 代理缺省字面量做守卫断言（vite.config.ts 为既有工程配置、非 src 实现代码）。
 * 「pnpm dev/build 实跑语义验证」= TL 执行项（本用例为配置层守卫）。
 *
 * 【表面假定（REQ20261114120103 套件内连续编号）】
 *   A-16 分层文件位于 web/ 根（Vite 根目录，import.meta.url 相对解析）；dev 语义=
 *       WEB_API_TARGET 未设（沿用 vite.config 缺省）或显式 localhost:9888；prod 语义=
 *       未设（保留部署管线运行时/构建注入）或显式合法 http(s) URL——「既有语义不变」的
 *       守卫面=dev 缺省 localhost:9888 + prod 可注入性，不锁死 prod 具体目标值。
 *   A-17 vite.config.ts 代理缺省字面量 'http://localhost:9888'（既有语义锚）存续。
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';

vi.setConfig({ testTimeout: 15000 });

const readWebFile = (name: string): string | null => {
  try {
    return readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');
  } catch {
    return null;
  }
};

/** 解析 .env 文件中 WEB_API_TARGET 的赋值（strip 引号与行尾空白）；未声明返回 null。 */
const readWebApiTarget = (content: string | null): string | null => {
  if (content === null) return null;
  const m = content.match(/^\s*WEB_API_TARGET\s*=\s*(.+?)\s*$/m);
  if (!m) return null;
  return m[1].replace(/^['"]|['"]$/g, '').trim();
};

const isLocalhost9888 = (value: string) =>
  /^(https?:\/\/)?localhost:9888\/?$/i.test(value);

const isHttpUrl = (value: string) => /^https?:\/\/.+/i.test(value);

describe('REQ20261114120103 env 分层（AC-8）', () => {
  it('should_layer_env_files_when_development_and_production', () => {
    const dev = readWebFile('.env.development');
    const prod = readWebFile('.env.production');
    // 分层建立：两文件存在且非空（A-16）
    expect(dev).not.toBeNull();
    expect(prod).not.toBeNull();
    expect(dev!.trim().length).toBeGreaterThan(0);
    expect(prod!.trim().length).toBeGreaterThan(0);
  });

  it('should_keep_localhost_9888_semantics_for_development', () => {
    // dev 语义不变（A-16）：未设（沿用 vite.config 缺省）或显式 localhost:9888
    const target = readWebApiTarget(readWebFile('.env.development'));
    expect(target === null || isLocalhost9888(target)).toBe(true);
  });

  it('should_keep_injection_semantics_for_production', () => {
    // prod 语义不变（A-16）：未设（保留部署管线注入）或显式合法 http(s) URL
    const target = readWebApiTarget(readWebFile('.env.production'));
    expect(target === null || isHttpUrl(target)).toBe(true);
  });

  it('should_keep_vite_proxy_default_fallback_localhost_9888', () => {
    // 既有语义锚（A-17）：vite.config.ts 代理 target 缺省字面量存续
    const config = readWebFile('vite.config.ts');
    expect(config).not.toBeNull();
    expect(config).toMatch(/WEB_API_TARGET\s*\?\?\s*['"]http:\/\/localhost:9888['"]/);
  });
});
