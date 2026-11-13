#!/usr/bin/env node
// AI 配置镜像预检（本地层）：推送策略分支前校验 acme-foundation / ai-coding-support 两分支 AI 路径逐字节一致，
// 口径与 CI mirror-sync 作业相同，避免「推完才发现红灯」。
// 用法：node .ai/hooks/mirror-check.mjs [acmeSha] [supportSha]
//   参数为空或全零时回退到本地分支 HEAD；任一侧缺失即失败（无法确认一致性不得放行）。
import { execFileSync } from 'node:child_process';

const AI_PATHS = [
  '.ai',
  '.ai-memory',
  '.github',
  '.claude',
  '.cursor',
  '.gemini',
  '.iflow',
  '.trae',
  '.qoder',
  '.gitignore',
  'AGENTS.md',
  'CLAUDE.md',
  'GEMINI.md',
  'CODEBUDDY.md',
  ':(glob)**/AGENTS.md',
  ':(glob)**/CLAUDE.md',
];
const ZERO = '0'.repeat(40);
const [MAIN_BRANCH, SUPPORT_BRANCH] = ['acme-foundation', 'ai-coding-support'];

const git = (args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });

function resolve(arg, branch) {
  const ref = arg && arg !== ZERO ? arg : `refs/heads/${branch}`;
  try {
    return git(['rev-parse', '--verify', '--quiet', ref]).trim();
  } catch {
    throw new Error(`找不到 ${branch} 的提交（${ref}）；请先 git fetch origin 并确认本地存在该分支。`);
  }
}

function isConsistent(a, b) {
  try {
    git(['diff', '--quiet', a, b, '--', ...AI_PATHS]);
    return true;
  } catch (error) {
    if (error.status === 1) return false;
    throw error;
  }
}

let mainSha;
let supportSha;
try {
  mainSha = resolve(process.argv[2], MAIN_BRANCH);
  supportSha = resolve(process.argv[3], SUPPORT_BRANCH);
} catch (error) {
  console.error(`推送被拒绝：${error.message}`);
  process.exit(1);
}

if (isConsistent(mainSha, supportSha)) {
  console.log(`✅ AI 配置镜像一致：${mainSha.slice(0, 7)} ↔ ${supportSha.slice(0, 7)}`);
  process.exit(0);
}

console.error('推送被拒绝：AI 配置在两条策略分支间不一致（CI mirror-sync 会判红）。');
try {
  const stat = git(['diff', '--stat', mainSha, supportSha, '--', ...AI_PATHS]).trim();
  if (stat) console.error(stat);
} catch {
  // diff --stat 仅用于提示，展示失败不影响结论
}
console.error('请先对齐未镜像的一方（cherry-pick 或按路径 checkout），再用同一条命令推送两分支：');
console.error('  git push origin acme-foundation ai-coding-support');
process.exit(1);
