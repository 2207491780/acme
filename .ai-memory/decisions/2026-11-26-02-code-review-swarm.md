# 新增 code-review 评审蜂群（manual 档、唯一评审 md、工人临时脚本边界、跨平台硬规范）

日期：2026-11-26

## 背景

仓库已有 `ai-coding-dev` 蜂群（`engine.ai-coding-dev: auto`，四阶段契约流程，产物为 `contracts/<REQ-ID>.md`），但缺少一套**全面代码评审**的蜂群流程：评审需要多视角并行（契约、双端实现、链路、影响、质量）、动态取证与裁决，且不应产生新的 REQ 或第二份入库文档。用户已审核通过 V1 最终稿，并追加一处修改：**工人角色也可写审核脚本**（此前仅限 leader）。因此需要在 `.ai/` 源中新增独立蜂群，同时扩展触发引擎的档位，并给"写脚本"设定严格且跨平台的边界。

## 决定

1. **蜂群与标识**：新增蜂群 `code-review`，角色源统一放 `.ai/agents/code-review/`（9 个角色，文件名与既有 6 个 agent 零冲突），leader 为 `code-review-leader.md`。评审号 `REVIEW-YYYYMMDD-HHMMSS`（秒级时间戳，天然防撞），评审开始时生成，不预占、不查重、不用工作树取号锁。
2. **manual 触发档**：`engine.code-review: manual`；扩展 `sync.mjs` 的 `SWARM_TRIGGER_MODES` 为 `['auto','always','never','manual']` 并在 `buildSwarmTrigger()` 增加 manual 文案（编号按蜂群名排序从 5 递增，本仓库 `ai-coding-dev`=5、`code-review`=6）。`manual` = 仅用户对话显式要求时调用，无自动触发、无命令入口；**不创建 `/review`、不创建 ZCode/各工具命令指针**；触发段不含 leader/手册/命令引用。`ARCHITECTURE.md` 引擎开关表同步增加 manual 档说明；`AGENT-POOL.md` 登记双蜂群并存。
3. **唯一产物与写入权限**：唯一入库文件 `contracts/<REVIEW-ID>.md`（七节结构：结论 / 输入 / 分级意见 P0–P3 / 链路仿真与副作用 / 历史债隔离 / 证据清单 / 评审轮次记录）。**只有 leader 有 Write/Edit 写该文件**；多轮打回在同一文件第 7 节追加，不新建。`verdict-arbitrator` 纯只读（tools `[Read, Grep, Glob]`）、不落盘，结论回传 leader。**不产生 REQ**：不创建/不修改任何 `REQ-*.md`，不写第二份 md，不建独立 baseline，不写仓库内测试文件。与 `ai-coding-dev` 的关系：两蜂群并存、互不触发；评审蜂群不进入四阶段契约流程、不改业务代码。
4. **工人可写临时脚本的边界（用户修改项）**：除 leader 与 arbitrator 外的 7 个工人角色（`contract-preflight` / `module-inspector-be` / `module-inspector-fe` / `quality-reviewer` / `flow-simulator` / `cross-contract-verifier` / `impact-oracle`）的 Write/Bash **只能**把审核脚本写到 `.ai-tmp/code-review/<REVIEW-ID>/`，用完删除；**不得写仓库内任何文件、不得写第二份 md、不得写业务代码**。leader 的 Bash 也仅限 `node <script>.mjs`。
5. **跨平台硬规范（写进 Skill 与所有角色定义）**：① 一律 Node `.mjs` + `node` 运行，禁平台脚本后缀文件；② 统一执行器 `.ai/skills/code-review/scripts/run.mjs`，只用 `execFile(file, args[], { shell: false })`，禁拼 shell 字符串、禁带 shell 真值的执行方式、禁 `&&`/`|`/重定向；③ 禁平台专属命令（平台自带文本检索工具等）、禁硬编码绝对路径，路径一律 `path.resolve`/`path.join`，参数用正斜杠；④ Maven 解析零平台分支：`process.env.ACME_MVN` → PATH 中 `mvn` → 明确报错「请设置 ACME_MVN」，不写平台专用后缀名、不按 `process.platform` 分支；⑤ 清理用 `fs.rm(p, { recursive: true, force: true })`，不用递归强删命令；⑥ 环境变量只经 `process.env`，LF 换行、UTF-8 无 BOM，Node 路径用 `process.execPath`。
6. **阶段 0 事实清单（不写脚本）**：leader 用 Read/Grep/Glob 读固定事实源（工程结构 / 契约与后端 / 调用链 / 运行时与租户 / 规范文档），形成带 `file:line` 的清单随委派上下文下发；缺失写「基线缺口」，角色不得自行推导补充。
7. **动态取证三层**：L0 = 8 角色静态分析；L1 = leader 经 `run.mjs` 跑已有检查（sync --check / check-contract.mjs / pnpm typecheck / 命中模块既有 vitest / Maven 测试），不写新文件；L2 = 高危点写 `.ai-tmp/code-review/<REVIEW-ID>/probe*.mjs` 并 `node` 运行、结束 `fs.rm` 清理。分级映射与 `SKIP(原因)` 规则写入 Skill，L1/L2 结果进唯一 md 第 6 节。
8. **回归**：`.ai/tests/sync.test.mjs` 加 manual 注入 / 枚举 / 多蜂群编号（5、6）用例；`.ai/tests/swarm-docs.test.mjs` 加角色一致性、leader 唯一写入口、工人脚本边界断言；新增 `.ai/tests/code-review-scripts.test.mjs` 做执行器跨平台静态源码断言与临时目录清理验证（配置引擎回归，非业务评审测试）。

## 被否的选项

- **给评审做 `/review` 命令并派生到各工具**：否决——manual 档定义即"仅显式对话调用"，命令入口会变成隐式入口且与"无命令入口"规格冲突。
- **评审结论写进 `contracts/<REQ-ID>.md` 或新建 `REVIEW-*.baseline`**：否决——评审不是需求，不产生 REQ；第二份文件会造成状态双写与轮次漂移，唯一 md 第 7 节足够承载多轮。
- **让工人直接 Write/Edit 唯一评审 md（并行追加）**：否决——并发写同一文件会互相覆盖且无仲裁；收口到 leader 唯一写入口，工人只回传结论。
- **允许工人用 shell 脚本 / 平台自带文本检索 / 硬编码绝对路径**：否决——评审脚本必须在 Windows 与 POSIX 一致可跑，平台分支与 shell 拼接会带来注入与漂移；统一执行器 `run.mjs` + `.mjs` + `fs.rm` 是唯一跨平台口径。
- **把临时脚本写进仓库内（如 `.ai/skills/code-review/scripts/` 或模块目录）**：否决——会污染入库产物、误当业务代码并被钩子/CI 校验；只在被 git 忽略的 `.ai-tmp/code-review/<REVIEW-ID>/` 落盘并清理。
- **复用 `ai-coding-dev` 的 `team-leader` 技能与四阶段流程**：否决——评审与开发是不同生命周期（评审号 vs 需求号、报告 vs 契约、manual vs auto），复用会混淆产物与门禁口径。
