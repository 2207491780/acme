# 统一三层 AI 路径口径：pre-commit 暂存清单补齐 .ai-memory（修正 ADR-03 表述）

日期：2026-11-14

## 背景

2026-11-14 全面审查发现三层防线口径不一致：pre-commit 暂存清单（`.ai/hooks/pre-commit` 的 `set --` 行）不含 `.ai-memory`，而 `mirror-check.mjs` 的 AI_PATHS 与 CI mirror-sync 作业均包含；`.ai-memory/playbooks/parallel-dev.md` 声称「`.ai/` 与 `.ai-memory/` 的修改同一时刻只允许一个会话持有（pre-commit 的 AI 配置原子提交契约）」，ADR 2026-11-13-03 也写「与 pre-commit 暂存清单同口径」——实现与两处文档矛盾。后果：半暂存的 `.ai-memory` 改动本地提交放行，到 pre-push/CI 才被拦，防线反馈延迟，且手册承诺的会话互斥并未机械生效。

## 决定

1. pre-commit 暂存清单加入 `.ai-memory`，与 mirror-check/CI mirror-sync 完全同口径（三层统一为：`.ai .ai-memory .github .claude .cursor .gemini .trae .qoder .gitignore` + 根部四入口 + `**/AGENTS.md` `**/CLAUDE.md`）。
2. 语义：AI 配置原子提交契约扩展到 `.ai-memory`——存在未暂存/未跟踪的入库内容即拒绝提交（`*.local.md` 已 gitignore，不受影响）。
3. parallel-dev.md 表述经核对与实现一致后保留；本记录取代 ADR 2026-11-13-03 中「与 pre-commit 暂存清单同口径」的表述（该句在实现未含 `.ai-memory` 时失真；ADR 只增不改，以本记录修正口径）。
4. 回归锁定：`hooks.test.mjs` 新增用例，断言暂存清单含 `.ai-memory` 且不含已裁撤的 `.iflow`。

## 被否的选项

- **反向收窄（mirror/CI 去掉 `.ai-memory`）**：否决——镜像门禁必须覆盖知识记忆（ADR-03 背景中的 9 文件偏差事故正是记忆失镜像导致），收窄等于放弃既有防线。
- **只改文档口径（承认 pre-commit 不覆盖 .ai-memory）**：否决——手册声称的互斥语义有价值，机械实现优于口头约定，实现成本一行。
