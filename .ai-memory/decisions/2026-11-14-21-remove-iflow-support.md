# 裁撤 iFlow CLI 支持（官方 2026-04-17 关停）

日期：2026-11-14

## 背景

2026-11-14 ai-coding 全面审查发现：iFlow CLI 官方公告将于 2026-04-17（北京时间）正式关停并建议迁移 Qoder（platform.iflow.cn/cli/changelog 顶部横幅），早于审查时点 7 个月。仓库仍在维护全套支持：`sync.mjs` 的 POOLS 三个派生目标（`.iflow/agents|workflows|commands`）+ TOOLS 条目 + `.ai/tools/iflow/` 源 + 仓库 `.iflow/` 派生（12 文件）+ README/ARCHITECTURE/TUTORIAL/GUIDE/PROMPTS/池说明/naming 豁免清单等约 10 处文档引用。维护成本持续支出而生效价值为零。

## 决定

1. 引擎登记裁撤：POOLS 移除 `.iflow` 三条 targets；TOOLS 移除 `iflow` 条目；删除 `.ai/tools/iflow/IFLOW-CLI.md` 源与仓库 `.iflow/` 全部派生文件（13 个）。
2. AI 路径清单同步收窄：pre-commit 暂存清单、`mirror-check.mjs` AI_PATHS、`ci.yml` mirror-sync 路径移除 `.iflow`（目录已不存在，保留徒增口径噪音）。
3. 文档清理：README 厂商表删行、ARCHITECTURE 派生一览、TUTORIAL 第 10 节（标准族重排 11→10）与对照表、GUIDE 案例 3/4/5/9、PROMPTS 通用模板/案例 8/9/工具差异表、AGENT-POOL/SKILL-POOL/`tools/claude/CLAUDE-CODE.md`/PLAYBOOK-GUIDE/naming 豁免清单的引用全部移除。
4. 此前提及 `.iflow` 的 ADR（如 2026-11-14-19）为历史记录，按「只增不改」不动。

## 被否的选项

- **保留派生、仅文档标注「已停用」**：否决——引擎仍会派生与校验死目标，`--check` 在无 `.iflow` 的新机器报缺失，等于把死代码留在防线路径上。
- **迁移到 Qoder 专属配置**：否决——Qoder 支持已独立存在（`alwaysApply` 存根 + 专属目录），iFlow 的 agents/workflows 形态在 Qoder 无对应目录，无可迁移实体。
- **审查时先斩后奏直接删**：否决——删除属任务级变更，随审查方案（批次 A1）经用户确认后执行。
