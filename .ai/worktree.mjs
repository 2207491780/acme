#!/usr/bin/env node
// 本地 AI 开发工作树管理：node .ai/worktree.mjs add|remove|list|gc
// 口径见 .ai-memory/playbooks/parallel-dev.md；同一分支不得在两个工作树同时检出。
// gc（2026-11-20）：已 push 的干净工作树自动清理——候选=检出分支 tip 已是 origin/acme-foundation
// 祖先（内容已随主线推上远端）且工作树无未提交修改；镜像常驻检出（ai-coding-support）与主工作树
// 永不触碰；分支一律保留（删除按「AI 操作授权分级」）。add 时自动 GC（静默尽力，失败不阻塞）。
// gc --branches：追加回收本地分支——tip 已随主线 push 且未检出于任何工作树的非保护分支
// （acme-foundation/main/ai-coding-support 永不触碰；未推送分支删除属授权自主级）。
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';

const MAIN_BRANCH = 'acme-foundation';
const MIRROR_BRANCH = 'ai-coding-support';
const git = (args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const gitCwd = (cwd, args) => execFileSync('git', args, { encoding: 'utf8', cwd }).trim();
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
const unquoted = (p) => (p.startsWith('"') && p.endsWith('"') ? p.slice(1, -1) : p);
const usage = () => {
  console.error('用法：node .ai/worktree.mjs add <分支名> [基点] | remove <分支名> | list | gc [--dry-run] [--branches]');
  process.exit(2);
};

// 已 push 判定：分支 tip 已随主线推上远端（origin/acme-foundation 含它）。用本地远端跟踪 ref，
// 不做网络操作；无 origin 或未推过 → 恒 false（宁可不清，不可误删）。
const isPushed = (branch) => {
  try {
    git(['merge-base', '--is-ancestor', branch, `origin/${MAIN_BRANCH}`]);
    return true;
  } catch {
    return false;
  }
};

// 遍历工作树（porcelain 分块；返回 { dir, branch }，主工作树/detached 不在列）。
const listBranchWorktrees = () =>
  git(['worktree', 'list', '--porcelain'])
    .split('\n\n')
    .filter(Boolean)
    .map((block) => {
      const lines = block.split('\n');
      const wt = lines.find((l) => l.startsWith('worktree '));
      const br = lines.find((l) => l.startsWith('branch refs/heads/'));
      if (!wt || !br) return null;
      const dir = norm(unquoted(wt.slice('worktree '.length)));
      if (dir === root) return null; // 主工作树永不触碰
      return { dir, branch: br.slice('branch refs/heads/'.length) };
    })
    .filter(Boolean);

/**
 * GC：清理「已 push（分支 tip ⊆ origin/acme-foundation）且无未提交修改」的工作树。
 * 返回 { cleaned: string[], skipped: string[] }；单个移除失败仅告警不中断（尽力而为）。
 */
const gcWorktrees = ({ dryRun }) => {
  try {
    git(['worktree', 'prune']); // 先清悬空管理记录（目录硬删/半移除残留）
  } catch {
    // 非致命：prune 失败不阻塞逐个判定
  }
  const cleaned = [];
  const skipped = [];
  for (const { dir, branch } of listBranchWorktrees()) {
    if (branch === MIRROR_BRANCH) {
      skipped.push(`${branch}（镜像对齐常驻检出，跳过）`);
      continue;
    }
    const dirty = (() => {
      try {
        return gitCwd(dir, ['status', '--short']).length > 0;
      } catch {
        return true; // 状态不可判（目录被外部占用/损坏）：宁可不清
      }
    })();
    if (dirty) {
      skipped.push(`${branch}（有未提交修改，跳过）`);
      continue;
    }
    if (!isPushed(branch)) {
      skipped.push(`${branch}（分支内容未随主线 push 到远端，跳过）`);
      continue;
    }
    if (dryRun) {
      cleaned.push(`${branch}（${dir}）[dry-run 未实际移除]`);
      continue;
    }
    try {
      git(['worktree', 'remove', dir]);
      if (branch && !git(['branch', '--list', branch]).includes(branch)) {
        throw new Error(`分支 ${branch} 在移除工作树后丢失`);
      }
      cleaned.push(`${branch}（${dir}）`);
    } catch (error) {
      skipped.push(`${branch}（移除失败：${error.message}）`);
    }
  }
  return { cleaned, skipped };
};

const reportGc = ({ cleaned, skipped }, { verbose }) => {
  if (verbose) {
    for (const line of cleaned) console.log(`♻️ 已清理：${line}`);
    for (const line of skipped) console.log(`⏭️ 保留：${line}`);
    console.log(`✅ GC 完成：清理 ${cleaned.length} 个，保留 ${skipped.length} 个（分支一律保留；删除按授权分级）`);
  } else if (cleaned.length > 0) {
    console.log(`♻️ 已自动清理 ${cleaned.length} 个已 push 的干净工作树：${cleaned.join('、')}`);
  }
};

const KEEP_BRANCHES = new Set([MAIN_BRANCH, 'main', MIRROR_BRANCH]);

/**
 * 分支 GC（--branches 显式开启）：删除「tip 已随主线 push 到远端（origin/acme-foundation 含它）」
 * 且未检出于任何工作树的非保护分支——未推送分支删除属授权自主级，安全；保护分支
 * （acme-foundation / main / ai-coding-support）与检出中分支永不触碰。
 */
const gcBranches = ({ dryRun }) => {
  const checkedOut = new Set(listBranchWorktrees().map(({ branch }) => branch));
  const deleted = [];
  const kept = [];
  for (const raw of git(['branch', '--format=%(refname:short)']).split('\n').filter(Boolean)) {
    const branch = raw.trim();
    if (KEEP_BRANCHES.has(branch)) {
      kept.push(`${branch}（保护分支，跳过）`);
      continue;
    }
    if (checkedOut.has(branch)) {
      kept.push(`${branch}（检出于工作树，跳过）`);
      continue;
    }
    if (!isPushed(branch)) {
      kept.push(`${branch}（内容未随主线 push 到远端，跳过）`);
      continue;
    }
    if (dryRun) {
      deleted.push(`${branch} [dry-run 未实际删除]`);
      continue;
    }
    try {
      git(['branch', '-D', branch]);
      deleted.push(branch);
    } catch (error) {
      kept.push(`${branch}（删除失败：${error.message}）`);
    }
  }
  return { deleted, kept };
};

const [cmd, ...rest] = process.argv.slice(2);
if (cmd === 'list') {
  console.log(git(['worktree', 'list']));
  process.exit(0);
}

if (cmd === 'gc') {
  const dryRun = rest.includes('--dry-run');
  const withBranches = rest.includes('--branches');
  reportGc(gcWorktrees({ dryRun }), { verbose: true });
  if (withBranches) {
    const { deleted, kept } = gcBranches({ dryRun });
    for (const line of deleted) console.log(`🪦 已删分支：${line}`);
    for (const line of kept) console.log(`⏭️ 保留分支：${line}`);
    console.log(`✅ 分支 GC 完成：删除 ${deleted.length} 个，保留 ${kept.length} 个`);
  }
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
  // 自动 GC（静默尽力）：每次开工先回收已 push 的干净工作树；失败不阻塞创建
  try {
    reportGc(gcWorktrees({ dryRun: false }), { verbose: false });
  } catch {
    // GC 异常绝不阻塞 add
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
  // 防御校验（REQ-20261119-063129/S12 与 S13 期间两次实证）：remove 后分支 ref 必须仍存在，
  // 丢失（如目录硬删竞态）会导致分支指向的提交悬空、后续合并丢失全部工作。
  const branchStillThere = git(['branch', '--list', branch]).includes(branch);
  if (branch && !branchStillThere) {
    console.error(`❌ 防御校验失败：分支 ${branch} 在移除工作树后丢失，请立即检查 .git/refs/heads`);
    process.exit(1);
  }
  console.log(`✅ 工作树已移除：${dir}`);
  if (branch) console.log(`   分支 ${branch} 未自动删除（授权分级：删已推送分支须指令）；确认后 git branch -d ${branch}`);
  process.exit(0);
}

usage();
