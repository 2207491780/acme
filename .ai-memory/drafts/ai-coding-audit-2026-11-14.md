# ai-coding 全面审查（2026-11-14）——结论与处置台账

> 审查范围：生效性（逐客户端）+ 冗余 + 工程结构。方式：通读全部配置源/引擎/钩子/测试、实测 sync --check 与回归套件与 mirror-check、web 检索核对客户端约定。
> 处置：三批次修复已在 `fix/ai-coding-audit-remediation` 分支实施（本文为台账）；决策见 ADR 2026-11-14-21/22/23/24。

## 一、生效性结论

- ✅ 实测生效：Claude Code、Codex/AGENTS.md 标准族、ZCode（本会话即证据：入口注入 + 4 子代理注册）、Cursor、Qoder、Trae、Copilot、CodeBuddy。
- ⚠️ Gemini：`.gemini/agents/` 约定正确（v0.24+），但池内 tools 用 Claude 工具名 → 已修：引擎派生时映射 Gemini 内置名 + 移除 model: inherit（ADR-24）；**仍需用户装 Gemini CLI 冒烟确认加载**。
- ❌ iFlow：官方 2026-04-17 关停（changelog 横幅，建议迁移 Qoder）→ 已裁撤全套（ADR-21）。
- ZCode `/req` 命令：存根存在、agents 生效，但会话内未见注册——**待用户在 ZCode UI 输入 /req 冒烟**；未生效则调研其 workspace commands 格式后修 `.ai/tools/zcode/commands/req.md`。
- 机制缺口：孤儿检测曾只扫 TOOLS 目录 → 已扩展（根部非递归 + scopes 同级目录，新增 2 用例；顺带修复 inspectPath 对仓库根返回 null 的缺陷）。

## 二、处置台账（18 项）

| # | 问题 | 状态 |
|---|---|---|
| 1 | iFlow 死代码 | ✅ ADR-21 |
| 2 | pre-commit 缺 .ai-memory（三层口径不一） | ✅ ADR-22 + 测试 |
| 3 | RULES.md 预算 162B | ✅ 瘦身 10078→9137B（余量 1103B） |
| 4 | Gemini 工具名映射 | ✅ 引擎 transform + ADR-24（待冒烟） |
| 5 | ZCode /req 冒烟 | ⏳ 待用户 UI 验证 |
| 6 | 孤儿检测盲区 | ✅ 引擎扩展 + 测试 |
| 7 | ai-rules-sync 与 ci ai-config 重复 | ✅ ADR-23 裁撤 |
| 8 | Maven 路径 6 处 | ✅ 收敛：唯一口径源=flex-test-assembly「构建与运行」；backend-dev 委派单与 BACKEND.md 脚本块按自包含原则保留字面量 |
| 9 | cd.yml TARGET_ENV 步骤级重复 | ✅ 已删 |
| 10 | 12 个已合并本地分支 | ✅ git branch -d（自主档：未推送本地分支） |
| 11 | worktree 目录名脱钩（feat-view-prototype↔feat/login-prototype） | ⏳ 保留：现场有未提交改动 + 1 未合并提交（原型登录接线），需用户处置 |
| 12 | feat/view-prototype 未合并 | ⏳ 保留（先前台账误记为已合并，实测未合并） |
| 13 | environment.file 不校验存在 | ✅ 折中校验（.ai/env/ 在位才校验）+ 测试 |
| 14 | Assisted-by 无机器校验 | ✅ 规则注明「暂无机器校验，PR 描述审查把关」（git-workflow.md） |
| 15 | ai-config-editor 未进 ZCode | ✅ `.ai/tools/zcode/agents/ai-config-editor.md` 存根源已建，派生 localOnly |
| 16 | 双 ADR 区 | ✅ ADR-GUIDE 加豁免行（原型区收编存档） |
| 17 | 根 docs/ 空壳 | 保留不动（未来设计/API 文档区，用户未要求删） |
| 18 | 非 Claude 工具二次读取 RULES.md | 设计取舍，不动（存根节选兜底） |

## 三、遗留待办（本分支合并后）

1. 用户侧冒烟：ZCode 输入 `/req`；装有 Gemini CLI 的话验证 `.gemini/agents/` 五个子代理可加载、工具限制生效。
2. worktree `.worktrees/feat-view-prototype` 处置（含未合并提交，用户裁决保留/合并/弃置）。
3. 合并回 `acme-foundation` 后在主工作树重跑 `node .ai/sync.mjs`（刷新主树派生与 `.zcode/` localOnly 派生），删除主树残留的 `.iflow/` 与 `.github/workflows/ai-rules-sync.yml`（孤儿检测/scopes 手删口径），再双分支同推。
4. 上轮遗留（review-deferred-items.md）中的引擎项已随本次解决：根部入口孤儿检测 ✅；其余（pre-push 零值 SHA 40 位、mirror-sync 对端缺失报错笼统、原型区 7 孤儿屏等）仍在，见该清单。
