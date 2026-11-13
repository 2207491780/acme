# 裁撤独立工作流 ai-rules-sync.yml，AI 配置远端校验收口到 ci.yml

日期：2026-11-14

## 背景

2026-11-14 全面审查（沿用 `drafts/review-deferred-items.md` 的登记项）确认：`.ai/ci/ai-rules-sync.yml` 与 `ci.yml` 的 ai-config 作业功能重复——前者仅运行 `node .ai/sync.mjs --check`（且只在触及 AI 配置路径时触发），后者运行同一校验外加派生引擎回归测试（`.ai/tests/`），并在全分支 push / PR 触发。两条工作流对同一漂移分别报红，排查时需看两处日志，无增益。

## 决定

1. 删除 `.ai/ci/ai-rules-sync.yml` 源与派生 `.github/workflows/ai-rules-sync.yml`；远端防线（联动校验）由 `ci.yml` 的 ai-config 作业唯一承担。
2. 文档同步：ARCHITECTURE「三道防线·远端层」与「CI/CD」表、CI-GUIDE 工作流一览、`tools/github/GITHUB-CONFIG.md`、GUIDE 故障排查表改为指向 ci.yml ai-config 作业；naming.md 配置示例行换例。
3. 此前提到 ai-rules-sync 的 ADR（如 2026-11-14-20）为历史记录，按「只增不改」不动。

## 被否的选项

- **保留 ai-rules-sync、反向裁撤 ci.yml 的 --check 步骤**：否决——ci.yml 作业还承担引擎回归测试，且 ai-rules-sync 的路径过滤触发只是省 runner 分钟，本仓库规模收益可忽略；留两条工作流反而制造双重红灯噪音。
- **路径过滤触发保留为「快速失败」通道**：否决——ai-config 作业本身是轻量 node 脚本校验（秒级），无需再分层。
