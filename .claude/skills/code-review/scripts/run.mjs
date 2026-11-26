#!/usr/bin/env node
/**
 * run.mjs — code-review 蜂群跨平台统一执行器（唯一外部命令入口）。
 *
 * 用法：
 *   node <run.mjs> <script.mjs> [args...]   # 用 node 运行审核脚本（execFile，shell 关闭）
 *   node <run.mjs> --mvn [args...]          # 零平台分支解析 Maven 并执行
 *   node <run.mjs> --cleanup <dir>          # 用 fs.rm 递归清理临时目录
 *
 * 硬规范：只用 execFile(file, args[], { shell: false })；不拼命令字符串；不按平台分支；
 * 路径经 path.resolve / path.join；Node 路径用 process.execPath；环境变量只经 process.env。
 */
import { execFile } from 'node:child_process';
import { rm } from 'node:fs/promises';
import { resolve } from 'node:path';

const [command, ...args] = process.argv.slice(2);

function fail(message) {
  console.error(message);
  process.exit(2);
}

/** 统一执行：参数数组直传，不经过任何字符串命令拼接。 */
function run(file, fileArgs, missingHint = '') {
  const child = execFile(file, fileArgs, { shell: false });
  child.stdout?.pipe(process.stdout);
  child.stderr?.pipe(process.stderr);
  child.on('error', (error) => {
    if (missingHint && error.code === 'ENOENT') console.error(missingHint);
    else console.error(`命令无法执行：${error.message}`);
    process.exitCode = 1;
  });
  child.on('close', (code, signal) => {
    if (signal) {
      console.error(`命令被信号终止：${signal}`);
      process.exitCode = 1;
    } else if (code !== null && code !== 0) {
      process.exitCode = code;
    }
  });
}

if (command === '--cleanup') {
  if (args.length !== 1) fail('用法: node run.mjs --cleanup <dir>');
  await rm(resolve(args[0]), { recursive: true, force: true });
  process.exit(0);
}

if (command === '--mvn') {
  // 零平台分支：环境变量优先；其次交给 Node 在 PATH 中解析；都没有则明确报错。
  const maven = process.env.ACME_MVN || 'mvn';
  run(maven, args, '请设置 ACME_MVN（环境变量 ACME_MVN 未设置，PATH 中也未找到 mvn）');
} else if (command) {
  run(process.execPath, [resolve(command), ...args]);
} else {
  fail('用法: node run.mjs <script.mjs> [args...]，或 --mvn [args...]，或 --cleanup <dir>');
}
