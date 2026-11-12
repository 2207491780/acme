#!/usr/bin/env node
// 本地 AI 并行开发工作树管理：node .ai/worktree.mjs add|remove|list
// 口径见 .ai-memory/playbooks/parallel-dev.md；同一分支不得在两个工作树同时检出。
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';

const git = (args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const norm = (p) => resolve(p).replaceAll('\\', '/');
const root = norm(git(['rev-parse', '--show-toplevel']));
const dirFor = (branch) => norm(join(root, '.worktrees', branch.replaceAll('/', '-')));
const branchExists = (branch) => {
  try {
    git(['rev-parse', '--verify', '--quiet', `refs/heads/${branch}`]);
    return true;
  } catch {
    return false;
  }
};
const usage = () => {
  console.error('用法：node .ai/worktree.mjs add <分支名> [基点] | remove <分支名> | list');
  process.exit(2);
};

const [cmd, ...rest] = process.argv.slice(2);
if (cmd === 'list') {
  console.log(git(['worktree', 'list']));
  process.exit(0);
}

if (cmd === 'add') {
  const branch = rest[0];
  const base = rest[1] || 'HEAD';
  if (!branch || !/^[A-Za-z0-9._/-]+$/.test(branch)) usage();
  if (!/^(feat|fix|chore|docs|refactor|test|perf)\//.test(branch)) {
    console.error(`⚠️  分支名「${branch}」不符合分支模型（feat|fix|chore|docs|refactor|test|perf/<简述>），继续创建。`);
  }
  if (branchExists(branch)) {
    console.error(`分支「${branch}」已存在；同一分支不得检出于两个工作树，请用新分支。`);
    process.exit(1);
  }
  const dir = dirFor(branch);
  git(['worktree', 'add', '-b', branch, dir, base]);
  console.log(`✅ 工作树已创建：${dir}（分支 ${branch}，基点 ${base}）`);
  console.log('   进入该目录工作；根 AGENTS.md 与分层 AGENTS.md 随检出自带，钩子经共享 core.hooksPath 生效。');
  console.log('   需要跑前端时：cd workspace/apps/frontend && pnpm install（pnpm 共享 store，安装快）；后端：acme-cloud/ 下 mvn test。');
  console.log(`   收尾：node .ai/worktree.mjs remove ${branch}（删除分支按「AI 操作授权分级」取得指令）。`);
  process.exit(0);
}

if (cmd === 'remove') {
  const name = rest[0];
  if (!name) usage();
  const dir = name.includes('/') ? dirFor(name) : norm(join(root, '.worktrees', name));
  const blocks = git(['worktree', 'list', '--porcelain']).split('\n\n');
  const block = blocks.find((b) => b.split('\n').some((l) => l === `worktree ${dir}`));
  if (!block) {
    console.error(`未找到工作树：${dir}（node .ai/worktree.mjs list 查看）`);
    process.exit(1);
  }
  const branchLine = block.split('\n').find((l) => l.startsWith('branch refs/heads/'));
  const branch = branchLine ? branchLine.slice('branch refs/heads/'.length) : '';
  git(['worktree', 'remove', dir]);
  console.log(`✅ 工作树已移除：${dir}`);
  if (branch) console.log(`   分支 ${branch} 未自动删除（授权分级：删已推送分支须指令）；确认后 git branch -d ${branch}`);
  process.exit(0);
}

usage();
