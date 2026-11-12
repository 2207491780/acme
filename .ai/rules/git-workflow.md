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
| 需指令 | commit、push、合并到主线、cherry-pick 跨分支、删除已推送分支 | 须明确指令且不外扩：「提交」不含「推送」，「推送」不含「删分支」 |
| 禁止 | 强推任意分支、rebase/reset 已推送的他人提交、`--no-verify` 绕钩子、直推 `main`、删共享远端分支、绕过钩子/CI 门禁 | 即使被指示也先说明风险，等待二次确认 |

- push 前核对目标分支：目标不是当前任务分支时停下确认，防止误推 `main`。
- AI 参与的提交在信息正文尾部加 trailer `Assisted-by: <工具名>`（如 `Assisted-by: ZCode`）；PR 描述仍须注明 AI 改动。

## 开发合流流程（本地开发 → 合并 → 推送）

禁止直接在主线 `acme-foundation` 开发提交，一律三步：① 在 `feat/`、`fix/` 分支或独立工作树内开发自检；
② 回主工作树合并（优先快进），合并后先验证（`sync --check`/测试/构建）；③ 验证通过再推送，
AI 配置变更同步镜像 `ai-coding-support`。功能分支默认留本地，推远端与删分支按授权分级。
