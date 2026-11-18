import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseUpdateConfig, resolveCloseAction } from '../src/shared/update-config.js';

// REQ-20261114-120111 desktop 自动更新 + 系统托盘
// 被测纯逻辑：src/shared/update-config.js（parseUpdateConfig / resolveCloseAction）
// 独立可跑：node --test（不依赖 Electron 运行时）

test('should_禁用自动检查_when_dev环境即使配置了更新URL', () => {
  // AC-5：dev（isPackaged=false）不产生外呼
  const result = parseUpdateConfig({
    env: { ACME_DESKTOP_UPDATE_URL: 'https://updates.example.com/latest.yml' },
    isPackaged: false,
  });
  assert.equal(result.autoCheckEnabled, false);
});

test('should_autoCheckEnabled为false_when未配置更新URL', () => {
  // AC-2 / AC-5：无 URL 恒为 false，无论是否打包
  const unpackaged = parseUpdateConfig({ env: {}, isPackaged: false });
  assert.equal(unpackaged.updateUrl, null);
  assert.equal(unpackaged.autoCheckEnabled, false);

  const packaged = parseUpdateConfig({ env: {}, isPackaged: true });
  assert.equal(packaged.updateUrl, null);
  assert.equal(packaged.autoCheckEnabled, false);
});

test('should_updateUrl为null_when_URL为空串或空白串', () => {
  // AC-2：空串视为 null（trim 后）
  const empty = parseUpdateConfig({ env: { ACME_DESKTOP_UPDATE_URL: '' }, isPackaged: true });
  assert.equal(empty.updateUrl, null);
  assert.equal(empty.autoCheckEnabled, false);

  const blank = parseUpdateConfig({ env: { ACME_DESKTOP_UPDATE_URL: '   ' }, isPackaged: true });
  assert.equal(blank.updateUrl, null);
  assert.equal(blank.autoCheckEnabled, false);
});

test('should_autoCheckEnabled为true_when_有URL且已打包', () => {
  // AC-2 前置条件（有 URL 才有检查动作）
  const result = parseUpdateConfig({
    env: { ACME_DESKTOP_UPDATE_URL: '  https://updates.example.com/latest.yml  ' },
    isPackaged: true,
  });
  assert.equal(result.updateUrl, 'https://updates.example.com/latest.yml');
  assert.equal(result.autoCheckEnabled, true);
});

test('should_updateUrl做trim处理_when_URL首尾带空白', () => {
  const result = parseUpdateConfig({
    env: { ACME_DESKTOP_UPDATE_URL: '\t https://updates.example.com/latest.yml \n' },
    isPackaged: true,
  });
  assert.equal(result.updateUrl, 'https://updates.example.com/latest.yml');
});

test('should_返回quit_when_closeToTray为falsy', () => {
  // AC-3：默认/未设置 → 直接退出
  assert.equal(resolveCloseAction(undefined), 'quit');
  assert.equal(resolveCloseAction(null), 'quit');
  assert.equal(resolveCloseAction(false), 'quit');
  assert.equal(resolveCloseAction(0), 'quit');
  assert.equal(resolveCloseAction(''), 'quit');
});

test('should_返回minimize-to-tray_when_closeToTray为truthy', () => {
  // AC-3：配置开启 → 关闭窗最小化到托盘
  assert.equal(resolveCloseAction(true), 'minimize-to-tray');
  assert.equal(resolveCloseAction(1), 'minimize-to-tray');
  assert.equal(resolveCloseAction('yes'), 'minimize-to-tray');
});
