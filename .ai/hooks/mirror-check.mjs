#!/usr/bin/env node
// AI 配置镜像预检（本地层）：推送策略分支前校验 acme-foundation / ai-coding-support 两分支 AI 路径逐字节一致，
// 口径与 CI mirror-sync 作业相同，避免「推完才发现红灯」。
// 用法：node .ai/hooks/mirror-check.mjs [acmeSha] [supportSha]
//   参数为空或全零时回退到本地分支 HEAD；任一侧缺失即失败（无法确认一致性不得放行）。
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// AI 路径单一清单：与本脚本同目录的 ai-paths.list（唯一事实源，pre-commit / CI mirror-sync 同口径；
// 一致性由 .ai/sync.mjs 与 .ai/tests/ai-paths.test.mjs 锁定；ADR 2026-11-14-22、2026-11-17-01）。
// 用脚本自身位置解析：钩子经 core.hooksPath 指向当前 worktree 的 .ai/hooks，任意 worktree 根都成立。
const AI_PATHS = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'ai-paths.list'), 'utf8')
  .split('\n')
  .map((line) => line.trim())
  .filter((line) => line && !line.startsWith('#'));

// 空 SHA（null OID）不硬编码长度：全 0 字符串判定，兼容 SHA-1（40 位）/SHA-256（64 位）仓库。
const isNullOid = (sha) => /^0+$/.test(sha);
const [MAIN_BRANCH, SUPPORT_BRANCH] = ['acme-foundation', 'ai-coding-support'];

const git = (args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });

function resolve(arg, branch) {
  const ref = arg && !isNullOid(arg) ? arg : `refs/heads/${branch}`;
  try {
    return git(['rev-parse', '--verify', '--quiet', ref]).trim();
  } catch {
    throw new Error(
      `本地缺少策略分支 ${branch}：${ref} 无法解析为提交。补救：git fetch origin 后执行` +
        ` git branch ${branch} origin/${branch}（或与对侧策略分支对齐后重建本地分支）；` +
        `两条策略分支（${MAIN_BRANCH} / ${SUPPORT_BRANCH}）必须同时存在才能预检镜像一致性。`
    );
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
