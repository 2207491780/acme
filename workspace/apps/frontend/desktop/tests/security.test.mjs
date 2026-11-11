import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isSafeExternalUrl } from '../src/shared/security.js';

test('isSafeExternalUrl 允许 http 与 https', () => {
  assert.equal(isSafeExternalUrl('https://example.com/path?q=1'), true);
  assert.equal(isSafeExternalUrl('http://localhost:5173/'), true);
});

test('isSafeExternalUrl 拒绝危险协议', () => {
  assert.equal(isSafeExternalUrl('file:///etc/passwd'), false);
  assert.equal(isSafeExternalUrl('javascript:alert(1)'), false);
  assert.equal(isSafeExternalUrl('data:text/html,<script>alert(1)</script>'), false);
  assert.equal(isSafeExternalUrl('ftp://example.com'), false);
  assert.equal(isSafeExternalUrl('chrome://settings'), false);
});

test('isSafeExternalUrl 拒绝非法或缺失输入', () => {
  assert.equal(isSafeExternalUrl(''), false);
  assert.equal(isSafeExternalUrl('not a url'), false);
  assert.equal(isSafeExternalUrl(null), false);
  assert.equal(isSafeExternalUrl(undefined), false);
  assert.equal(isSafeExternalUrl(12345), false);
  assert.equal(isSafeExternalUrl({}), false);
});