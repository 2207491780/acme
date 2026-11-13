#!/usr/bin/env node
// 启用本仓库 Git 钩子：core.hooksPath → .ai/hooks，并自检全部钩子文件在位。
// 非 Windows 下额外校验执行位：Git 对不可执行钩子静默跳过（不报错），仅查存在会给出假阳性
// （review 修复：非 clone 场景如手工复制/解包检出缺执行位）。
import { execFileSync } from 'node:child_process';
import { existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
execFileSync('git', ['config', 'core.hooksPath', '.ai/hooks'], { cwd: root });
const cur = execFileSync('git', ['config', '--get', 'core.hooksPath'], { cwd: root, encoding: 'utf8' }).trim();

const hooks = ['pre-commit', 'commit-msg', 'pre-push'];
const checkExec = process.platform !== 'win32';
const missing = hooks.filter((h) => {
  const path = join(root, '.ai/hooks', h);
  if (!existsSync(path)) return true;
  if (checkExec && !(statSync(path).mode & 0o111)) return true;
  return false;
});
if (cur !== '.ai/hooks' || missing.length > 0) {
  console.error(`启用失败：hooksPath=${cur || '<未设置>'}${missing.length ? `，缺失或不可执行的钩子：${missing.join(', ')}` : ''}`);
  if (missing.length > 0 && checkExec) {
    console.error('修复：git update-index --chmod=+x .ai/hooks/{pre-commit,commit-msg,pre-push}');
  }
  process.exit(1);
}
console.log(`✅ Git 钩子已启用（core.hooksPath=.ai/hooks）：${hooks.join(' / ')}`);
