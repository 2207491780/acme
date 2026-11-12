<!-- ⚠️ 本文件由 .ai/sync.mjs 从 .ai/rules/*.md 自动拼接生成，请勿直接修改；修改请编辑 .ai/rules/ 下的源文件并重新运行脚本。 -->

# ACME 项目规范

# AI 协作准则

> 各 AI Coding 工具在本仓库工作时必须遵守的行为准则与红线。

## 动手之前

1. **先读再改**：修改任何文件前，先阅读其上下文（同模块相关代码、既有测试），遵循现有风格，不擅自重构无关代码。
2. **最小改动**：只改与任务直接相关的代码；发现其他问题先报告，不顺手修改。
3. **不确定就问**：需求有歧义、涉及破坏性操作（删除、覆盖、强推）时，先向用户确认。
4. **临时文件归位**：所有临时/中间文件（脚本、草稿输出、日志等）一律放仓库根的 `.ai-tmp/`（git 忽略其内容、目录本身保留），不得散落在仓库其他位置。

## 安全红线

- ❌ 不读取、不输出、不提交任何密钥/凭证/个人隐私数据。
- ❌ 不执行 `rm -rf`、强推、删库等破坏性命令。
- ❌ 不修改 CI/CD 配置、锁文件、`.git/`，除非任务明确要求。
- ❌ 不引入未在 `.ai/rules/project.md` 登记的依赖。

## AI 配置联动更新（强制）

修改 AI Coding 相关内容时，**必须触发所有工具联动更新**，保证任何工具读到的规范颗粒度一致：

1. 所有 AI 配置的唯一事实源是 `.ai/`（公共规范 `.ai/rules/`、共享能力池 `.ai/agents|skills|commands/`、工具专属配置 `.ai/tools/<工具>/`），**禁止**直接修改任何工具目录下的派生文件（如 `CLAUDE.md`、`.cursor/rules/project.mdc`、`.claude/` 内的生成内容）。
2. 每次修改后**必须立即**运行 `node .ai/sync.mjs`，把变更派生到所有工具目录，再提交。
3. 提交前自检：`node .ai/sync.mjs --check` 必须通过；不同步的提交会被 pre-commit 钩子与 CI 拒绝。
4. **禁止只同步部分工具**：要么全部联动更新，要么不提交。

## AI 配置修改任务

凡任务涉及增删改 AI-Coding 配置（公共规范、分层规则、agents/skills/commands 公共池、工具专属配置、钩子、CI、派生引擎）：先完整阅读 `.ai-memory/playbooks/ai-config.md`，严格按其五步流程执行（定位意图 → 只改源 → `node .ai/sync.mjs` 派生 → `--check` 校验 → 汇报）。支持子代理/技能的工具，可直接调用 `ai-config-editor`（子代理）或 `ai-config`（技能）。

## 过程性内容落盘

会话中形成的重要结论**必须落盘**，不能只留在对话里（模型可见 ⟺ 已落盘）：

- **取舍与决策**（为什么）→ `.ai-memory/` 新增记录（三段式：背景/决定/被否的选项；**只增不改**，推翻旧决策用新记录标注取代）。
- **高频操作步骤**（怎么做）→ `.ai-memory/` 新增手册，并在规则中挂触发条款。
- **必须遵守的约束** → `.ai/rules/`。
- **工作记忆**（进行中事项、踩坑速记、待沉淀结论）→ `.ai-memory/drafts/`（草稿区，随时改写；个人草稿用 `*.local.md` 不入库；成熟的结论提炼进 decisions/playbooks/rules 后删除草稿）。
- 预算：`.ai/RULES.md`（全部规范拼接后）≤ 8KB；超限时细节移入 playbooks/decisions，规则只留一句结论加路径。

## 产出要求

1. 改动必须附带修改说明：改了什么、为什么、影响面。
2. 涉及业务逻辑的改动必须附测试，并在提交前运行全量测试。
3. 修完自检： lint / 类型检查 / 测试 三者通过才算完成。
4. 如实汇报结果：测试失败就报告失败，不掩饰、不跳过。

## 会话留痕

- 长任务定期总结进度到对话中，便于用户随时接管。
- 完成任务时给出：改动文件清单 + 验证结果 + 遗留事项。

# 编码规范

## 语言与格式

- 应用业务使用 TypeScript strict；Electron 与工程脚本使用现有 ESM/CJS，不混用模块格式。
- 沿用同目录的命名、缩进、引号和文件组织；UTF-8 无 BOM、LF 换行。
- React 组件/类型用 PascalCase，函数/变量用 camelCase，常量用 UPPER_SNAKE_CASE。
- 文件/目录/包/文档命名见 `.ai-memory/playbooks/naming.md`（总览/架构 md 大写、内容源 kebab、Java 包全小写）；重命名须任务明确要求。
- 注释只解释非显然的原因；TODO 需标注负责人或 issue。

## 边界与依赖

- JSON 导入、浏览器存储等外部输入必须做运行时校验，不能用类型断言代替；失败不得覆盖原数据。
- 异常必须明确处理；内存降级要告知用户未保存，不能只报告成功。
- 新增依赖先说明理由并核对 `.ai/rules/project.md`，优先使用标准库与现有依赖。

## 验证

- 行为修改必须附回归测试，覆盖失败分支；名称表达 `should_<行为>_when_<条件>`。
- 运行受影响端的类型检查、测试和构建；UI 修改需浏览器/设备验证，未执行的检查必须明确说明。
- 当前没有统一 lint 脚本，不得将类型检查或测试通过表述为 lint 通过；不得为过检查跳过失败用例。

# Git 工作流

> 所有 AI 工具执行 Git 操作时必须遵守。

## 分支模型

| 分支 | 用途 |
|---|---|
| `main` | 受保护，始终可发布；只接受合并，不直接推送 |
| `feat/<issue>-<简述>` | 功能开发 |
| `fix/<issue>-<简述>` | 缺陷修复 |
| `ai-coding-support` | AI 配置镜像分支：`.ai/`、`.ai-memory/` 等支撑变更同步落地 |

并行开发（git worktree）：每个 AI 会话用独立工作树，经 `node .ai/worktree.mjs add <分支名>` 创建
（落 `.worktrees/`，基点为当前 HEAD）；同一分支不得同时检出于两个工作树；收尾 `node .ai/worktree.mjs remove`。
流程见 `.ai-memory/playbooks/parallel-dev.md`。

## 提交信息规范（Conventional Commits）

```text
<type>(<scope>): <subject>

[可选正文：动机与影响]
```

- type 限定：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `perf`
- subject 用中文或英文祈使句，不超过 50 字，结尾不加句号
- 示例：`feat(auth): 增加手机号验证码登录`

## 提交粒度

- 一次提交只做一件事；生成代码与格式化调整分开提交。
- 禁止提交与本次任务无关的文件。

## 代码评审（CR/PR）

- PR 描述包含：改动目的、影响范围、自测方式。
- AI 生成的改动必须在 PR 描述中注明。
- CR 未通过前不得合并。

## 禁止事项

- ❌ 强推（force push）到共享分支
- ❌ 提交密钥、证书、`.env` 等敏感文件
- ❌ rebase / reset 已推送他人的提交
- ❌ 提交未联动同步的 AI 配置改动（修改 `.ai/` 后必须先运行 `node .ai/sync.mjs`，见 `.ai/rules/ai-guidelines.md` 的「AI 配置联动更新」）

## AI 操作授权分级

AI 工具执行 Git 操作按三档授权，越档视为违规；授权随用户指令产生，不因历史指令延续。

| 级别 | 操作 | 要求 |
|---|---|---|
| 自主 | 读操作、暂存、未推送本地分支的新建/切换/删除、运行检查与测试 | 无需确认 |
| 需指令 | commit、push、cherry-pick 跨分支、删除已推送分支 | 须明确指令且不外扩：「提交」不含「推送」，「推送」不含「删分支」 |
| 禁止 | 强推任意分支、rebase/reset 已推送的他人提交、`--no-verify` 绕钩子、直推 `main`、删共享远端分支、绕过钩子/CI 门禁 | 即使被指示也先说明风险，等待二次确认 |

- push 前核对目标分支：目标不是当前任务分支时停下确认，防止误推 `main`。
- AI 参与的提交在信息正文尾部加 trailer `Assisted-by: <工具名>`（如 `Assisted-by: ZCode`）；PR 描述仍须注明 AI 改动。

# 项目上下文

## 当前实现

ACME 是多端发布流程演示台，不是真实部署平台：发布单、审批、构建进度和产物均为本地模拟，没有后端鉴权或部署接口。Web 使用本地存储，移动端目前仅内存存储。

- 工程根 `workspace/apps/frontend/`；`packages/core/` 为框架无关业务/令牌，`web/` 为 React UI，`desktop/` 复用 Web 产物，`mobile/` 为 React Native。
- 实际栈：Node ≥22.13、pnpm 11.8、TypeScript 5、React 18、Vite 5、Ant Design 5、Zustand、Electron 37、Expo 52 / RN 0.76；测试为 Vitest 与 Node test。
- 未接入 TanStack Query、antd-mobile、electron-vite，不能假定这些依赖存在。
- 视觉事实源为 `packages/core/src/theme.ts`；UI 修改先读工程内 `UI-SPEC.md`，校验页用 `pnpm ui:guide` 生成，禁止手改生成页。

## 关键回归

外部数据加载/导入需验证实体、设置及关联；非法输入不得覆盖有效状态。删除撤销不能因 ID 复用丢记录，切换筛选不能误操作隐藏选项；发布目标变化不能沿用旧审批和产物。多窗口修改需检查存储冲突。

## 命令与门禁

在工程根运行 `pnpm typecheck`、`pnpm test`、`pnpm build`；入口为 `pnpm dev`、`pnpm desktop:dev`、`pnpm mobile:start`。桌面额外运行 `pnpm --filter @acme/desktop check`。

AI 修改运行 `node .ai/sync.mjs` 和 `node .ai/sync.mjs --check`。同步通过不代表钩子已启用；派生引擎回归测试在 `.ai/tests/`（CI ai-config 作业运行）。引擎开关见 `.ai/ai-coding-config.yml`。

环境连接定义在 `.ai/env/`，搭建脚本在 `workspace/env-stack/`；prod 默认只读，禁止明文密钥。
