#!/usr/bin/env node
/**
 * run.mjs — code-review 蜂群跨平台统一执行器（唯一外部命令入口）。
 *
 * 用法：
 *   node <run.mjs> [--timeout <ms>] <script.mjs> [args...]  # 用 node 运行审核脚本（execFile，shell 关闭）
 *   node <run.mjs> [--timeout <ms>] --mvn [args...]         # 经 Java 启动器执行 Maven（无需平台专属包装脚本）
 *   node <run.mjs> --cleanup <dir>                          # 用 fs.rm 递归清理临时目录（限 .ai-tmp/code-review/ 子目录）
 *
 * --timeout 放在子命令前；超时输出结构化 `SKIP(超时 <ms>ms，信号 <signal>)`，
 * 不把裸信号终止当作唯一口径；退出码沿用现有约定（异常=1、用法错误=2）。
 *
 * 硬规范：只用 execFile(file, args[], { shell: false })；不拼命令字符串；不按平台分支；
 * 路径经 path.resolve / path.join；Node 路径用 process.execPath；环境变量只经 process.env。
 *
 * Maven 解析：ACME_MVN 指向 Maven 安装目录下 bin/mvn 启动器；据此反推安装根，
 * 用 JAVA_HOME/bin/java（或 PATH 中的 java）加载 boot 下的 plexus-classworlds 启动器。
 * 这样不依赖任何带后缀的包装脚本、不开 shell，POSIX 与 NT 同一条命令路径。
 */
import { execFile } from 'node:child_process';
import { rm } from 'node:fs/promises';
import { readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { basename, dirname, join } from 'node:path';
import { isAbsolute, relative } from 'node:path';

const argv = process.argv.slice(2);
let timeoutMs;
if (argv[0] === '--timeout') {
  const raw = argv[1];
  if (raw === undefined || !/^\d+$/.test(raw) || Number(raw) <= 0) {
    fail('用法: node run.mjs [--timeout <ms>] <script.mjs>；--timeout 必须为正整数毫秒');
  }
  timeoutMs = Number(raw);
  argv.splice(0, 2);
}
const [command, ...args] = argv;

function fail(message) {
  console.error(message);
  process.exit(2);
}

/** 统一执行：参数数组直传，不经过任何字符串命令拼接。 */
function run(file, fileArgs, missingHint = '', timeout) {
  const child = timeout === undefined
    ? execFile(file, fileArgs, { shell: false })
    : execFile(file, fileArgs, { shell: false, timeout });
  child.stdout?.pipe(process.stdout);
  child.stderr?.pipe(process.stderr);
  child.on('error', (error) => {
    if (missingHint && error.code === 'ENOENT') console.error(missingHint);
    else console.error(`命令无法执行：${error.message}`);
    process.exitCode = 1;
  });
  child.on('close', (code, signal) => {
    if (signal) {
      if (timeout !== undefined) console.log(`SKIP(超时 ${timeout}ms，信号 ${signal}：${file})`);
      else console.error(`命令被信号终止：${signal}`);
      process.exitCode = 1;
    } else if (code !== null && code !== 0) {
      process.exitCode = code;
    }
  });
}

if (command === '--cleanup') {
  if (args.length !== 1) fail('用法: node run.mjs --cleanup <dir>');
  const target = resolve(args[0]);
  const reviewRoot = resolve('.ai-tmp', 'code-review');
  const rel = relative(reviewRoot, target);
  if (rel === "" || rel.startsWith('..') || isAbsolute(rel)) {
    fail(`越权清理：目标必须位于 .ai-tmp/code-review/ 之下的评审子目录（收到：${args[0]}）`);
  }
  await rm(resolve(target), { recursive: true, force: true });
  process.exit(0);
}

if (command === '--mvn') {
  const explicit = process.env.ACME_MVN;
  if (!explicit) fail('请设置 ACME_MVN（指向 Maven 安装目录下 bin/mvn 启动器）');
  const MVN_LAUNCHER = 'mvn';
  const launcherName = basename(explicit);
  if (!launcherName.startsWith(MVN_LAUNCHER)) {
    fail(`ACME_MVN 应指向 Maven 的 ${MVN_LAUNCHER} 启动器（实际：${launcherName}）`);
  }
  const home = resolve(dirname(dirname(explicit)));
  const boot = join(home, 'boot');
  let jars;
  try {
    jars = (await readdir(boot)).filter((f) => /^plexus-classworlds-.*\.jar$/.test(f)).sort();
  } catch (error) {
    fail(`无法读取 Maven 的 boot 目录（${boot}）：${error.message}`);
  }
  if (!jars.length) fail(`未在 ${boot} 找到 plexus-classworlds 启动器 jar`);
  const javaHome = process.env.JAVA_HOME;
  const java = javaHome ? join(resolve(javaHome), 'bin', 'java') : 'java';
  run(java, [
    '-classpath', join(boot, jars.at(-1)),
    `-Dclassworlds.conf=${join(home, 'bin', 'm2.conf')}`,
    `-Dmaven.home=${home}`,
    `-Dmaven.multiModuleProjectDirectory=${resolve('.')}`,
    'org.codehaus.plexus.classworlds.launcher.Launcher',
    ...args,
  ], '', timeoutMs);
} else if (command) {
  run(process.execPath, [resolve(command), ...args], '', timeoutMs);
} else {
  fail('用法: node run.mjs [--timeout <ms>] <script.mjs> [args...]，或 --mvn [args...]，或 --cleanup <dir>');
}
