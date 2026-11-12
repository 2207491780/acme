# 全仓库命名规范定稿：文档按角色二分，模块总览文档统一大写

> 约束源见 `.ai/rules/coding-standards.md`（拼接进所有工具入口）；完整口径见 `.ai-memory/playbooks/naming.md`。
> 本记录取代「不要批量重命名既有文件」的旧默认——改为「重命名须任务明确要求」，本次统一即明确要求。

## 背景

用户要求统一全仓库文件/文件夹/包命名并定义不同情况的格式（例：AI-Coding 架构相关 md 大写）。全量调研结论：

- **已一致**：自定义目录全 lowercase-kebab（`META-INF` 等框架固定名除外）；Java 包全小写零违规、类全 PascalCase；Maven 模块 kebab + 分层前缀；前端组件 PascalCase.tsx、模块 camelCase、`*.test.ts`；脚本/配置 kebab，框架固定名（application.yml/vite.config.ts/Dockerfile）一致。
- **唯一不一致**：5 个「目录总览」文档小写（`backend.md`/`frontend.md`/`web.md`/`desktop.md`/`mobile.md`），而同类 `WORKSPACE.md`/`APPS.md`/`ENV-STACK.md`/`ABOUT.md` 大写；且 `APPS.md` 旧约定明文写「与目录同名说明文件（如 web-console.md）」即小写。
- **预算约束**：`.ai/RULES.md` 拼接后 7998 bytes，逼近 `sync.mjs` 硬上限 8192（超限抛错且不写任何产物）；完整规范无法塞进 rules。
- **派生/不可动**：根入口与 `project_rules.md`/`copilot-instructions.md` 名字由 sync.mjs/工具固定；ADR 只增不改；`.idea/` 为 IDE 状态。

## 决定

1. **文档按角色二分**：导航/总览/架构/指南/入口（面向人）= UPPER-KEBAB；规范/手册/决策/代理/技能正文（面向 AI 消费或拼接）= lowercase-kebab。**目录总览文档 = 目录名大写**（kebab 目录 → UPPER-KEBAB.md），统一解释 WORKSPACE/APPS/ENV-STACK 与 BACKEND/FRONTEND/WEB/DESKTOP/MOBILE。
2. **完整规范落 playbook**（`.ai-memory/playbooks/naming.md`，覆盖 md/目录/Java 包类测试/Maven/前端/脚本配置/豁免清单/大小写红线），rules 仅留一句结论 + 路径指针；`PLAYBOOK-GUIDE.md` 登记并挂触发条款。拼接后 RULES.md = 8134 bytes < 8192。
3. **本次 sanctioned 重命名**：5 个模块总览 md 改大写（`git mv` 两步法，因 `core.ignorecase=true`），同步更新 `APPS.md` 约定文本与链接、`FRONTEND.md` 交叉引用、各文档自指。目录/Java 包/类/前端/脚本已一致，不动；派生与框架固定名豁免。
4. **历史与 IDE 不动**：ADR `2026-11-12-01-mobile-ui-native-rn.md` 对旧名的引用属历史快照，保留；`.idea/workspace.xml` 不改。

## 被否的选项

- **只定义规范、不改既有文件名**：否决——用户明确要求「统一」，保留不一致违背意图；旧「不要批量重命名」默认由本次明确任务取代。
- **反向统一为小写**（WORKSPACE/APPS/ENV-STACK 改小写以就 5 个模块文档）：否决——与体系文档大写的主流形态及用户「架构 md 大写」示例相悖，且改动面更大。
- **完整规范写进 `.ai/rules/`**：否决——RULES.md 已达 8KB 预算上限，细节须移入 playbook（规则只留结论 + 路径），否则 sync 抛错。
- **一并规范前端 kebab 变体（parse-state.ts 等）**：否决——前端本已自洽，churn 大收益低，违背最小改动。
- **重命名 Java 包/目录以求「绝对统一」**：否决——包/目录零违规，重命名会破坏 Maven 坐标、import、组件/映射扫描，纯风险无收益。