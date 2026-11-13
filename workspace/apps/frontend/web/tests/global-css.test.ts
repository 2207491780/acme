import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ACCENTS, DARK_NEUTRALS, LIGHT_NEUTRALS, TONES } from '@acme/core';

/**
 * global.css 令牌镜像锁定（review 修复）：global.css 自述「色值/字体镜像 @acme/core（唯一事实源）」，
 * 但此前无任何测试比对——改 core 色值时 AntD/RN 测试会失败，CSS 却静默漂移。
 * 本测试锁定暗色 :root、亮色 [data-theme="light"] 两个块与 core 令牌逐值一致。
 */
const css = readFileSync(new URL('../src/styles/global.css', import.meta.url), 'utf8');

function parseBlock(selectorPattern: RegExp): Map<string, string> {
  const body = css.match(selectorPattern)?.[1] ?? '';
  const vars = new Map<string, string>();
  for (const m of body.matchAll(/--([\w-]+):\s*([^;]+);/g)) {
    vars.set(`--${m[1]}`, m[2].trim());
  }
  return vars;
}

const darkVars = parseBlock(/^:root\s*\{([\s\S]*?)\}/m);
const lightVars = parseBlock(/:root\[data-theme="light"\]\s*\{([\s\S]*?)\}/m);

const darkMirror: [string, string][] = [
  ['--bg-0', DARK_NEUTRALS.bg],
  ['--bg-1', DARK_NEUTRALS.bg1],
  ['--surface', DARK_NEUTRALS.surface],
  ['--surface-2', DARK_NEUTRALS.elevated],
  ['--surface-3', DARK_NEUTRALS.surface3],
  ['--text', DARK_NEUTRALS.text],
  ['--text-2', DARK_NEUTRALS.text2],
  ['--muted', DARK_NEUTRALS.muted],
  ['--border', DARK_NEUTRALS.border],
  ['--glass-sider', DARK_NEUTRALS.glassSider],
  ['--glass-header', DARK_NEUTRALS.glassHeader],
  ['--cyan', ACCENTS.cyan],
  ['--blue', ACCENTS.blue],
  ['--violet', ACCENTS.violet],
  ['--magenta', ACCENTS.magenta],
  ['--amber', ACCENTS.amber],
  ['--acid', ACCENTS.acid],
  ['--teal', TONES.success],
  ['--red', TONES.danger],
  ['--slate', TONES.muted],
];

const lightMirror: [string, string][] = [
  ['--bg-0', LIGHT_NEUTRALS.bg],
  ['--bg-1', LIGHT_NEUTRALS.bg1],
  ['--surface', LIGHT_NEUTRALS.surface],
  ['--surface-2', LIGHT_NEUTRALS.elevated],
  ['--surface-3', LIGHT_NEUTRALS.surface3],
  ['--text', LIGHT_NEUTRALS.text],
  ['--text-2', LIGHT_NEUTRALS.text2],
  ['--muted', LIGHT_NEUTRALS.muted],
  ['--border', LIGHT_NEUTRALS.border],
  ['--glass-sider', LIGHT_NEUTRALS.glassSider],
  ['--glass-header', LIGHT_NEUTRALS.glassHeader],
];

describe('global.css 令牌镜像', () => {
  it('should_parse_both_theme_blocks_when_file_read', () => {
    expect(darkVars.size).toBeGreaterThan(0);
    expect(lightVars.size).toBeGreaterThan(0);
  });

  it.each(darkMirror)('should_mirror_dark_%s_from_core', (cssVar, token) => {
    expect(darkVars.get(cssVar)).toBe(token);
  });

  it.each(lightMirror)('should_mirror_light_%s_from_core', (cssVar, token) => {
    expect(lightVars.get(cssVar)).toBe(token);
  });

  it('should_alias_accent_to_cyan_by_default', () => {
    expect(darkVars.get('--accent')).toBe('var(--cyan)');
    expect(darkVars.get('--on-accent')).toBe('#000000');
  });
});
