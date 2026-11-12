#!/usr/bin/env node
// 启用本仓库 Git 钩子：core.hooksPath → .ai/hooks，并自检全部钩子文件在位。
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
execFileSync('git', ['config', 'core.hooksPath', '.ai/hooks'], { cwd: root });
const cur = execFileSync('git', ['config', '--get', 'core.hooksPath'], { cwd: root, encoding: 'utf8' }).trim();

const hooks = ['pre-commit', 'commit-msg', 'pre-push'];
const missing = hooks.filter((h) => !existsSync(join(root, '.ai/hooks', h)));
if (cur !== '.ai/hooks' || missing.length > 0) {
  console.error(`启用失败：hooksPath=${cur || '<未设置>'}${missing.length ? `，缺失钩子：${missing.join(', ')}` : ''}`);
  process.exit(1);
}
console.log(`✅ Git 钩子已启用（core.hooksPath=.ai/hooks）：${hooks.join(' / ')}`);
