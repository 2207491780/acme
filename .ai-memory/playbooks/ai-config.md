# AI 配置修改操作手册（ai-config）

> 供所有 AI Coding 工具执行「修改 AI 配置」类任务时使用。
> 触发入口：`.ai/rules/ai-guidelines.md`「AI 配置修改任务」（全部工具）；`ai-config-editor` 子代理 / `ai-config` 技能（支持的工具有效）。

## 适用范围

凡修改以下内容，都属于 AI 配置修改任务，必须按本手册执行：

- `.ai/rules/` 公共规范
- `.ai/scopes/` 分层规则
- `.ai/agents|skills|commands/` 跨工具公共池
- `.ai/tools/<工具>/` 工具专属配置
- `.ai/hooks/`、`.ai/ci/`、`.ai/sync.mjs`（钩子 / CI / 派生引擎）

## 操作流程（五步，缺一不可）

1. **定位意图**：明确要改什么（哪一类源、哪个工具）。不确定时对照 `.ai/ARCHITECTURE.md` 的「目录结构」与「派生一览」。
2. **只改源**：在 `.ai/` 对应源位置修改。**绝不**直接编辑任何派生文件（`AGENTS.md`、`CLAUDE.md`、`.ai/RULES.md`、`.claude/**`、`.cursor/**`、`.github/**` 等）。
3. **派生**：运行 `node .ai/sync.mjs`（需要链接模式时加 `--links`）。
4. **校验**：运行 `node .ai/sync.mjs --check`，必须输出「校验通过 … 无孤儿残留」；出现孤儿文件按提示手动移除后复检。
5. **汇报**：列出改动的**源文件** + 变化的**派生文件** + 校验输出原文，提醒用户连同派生结果一起提交。

## 常见任务速查

| 任务 | 修改位置（唯一源） |
|---|---|
| 改全局规范（所有工具生效） | `.ai/rules/*.md`（新增文件无需改脚本，按文件名排序拼接） |
| 某目录专属规则（就近生效） | `.ai/scopes/<仓库相对路径>/AGENTS.md` |
| 新增跨工具子代理 | `.ai/agents/<name>.md`（frontmatter 规范见该目录 `AGENT-POOL.md`） |
| 新增跨工具技能 | `.ai/skills/<name>/SKILL.md` |
| 新增斜杠命令 | `.ai/commands/<name>.md`（Gemini 需手工转 TOML 放 `.ai/tools/gemini/commands/`） |
| 某工具私有配置（settings / 私有规则 / .cursorignore…） | `.ai/tools/<工具>/`（镜像目标路径） |
| 新增兼容一个 AI 工具 | 流程见 `.ai/ARCHITECTURE.md`「新增一个 AI 工具的兼容」 |
| git 钩子 / CI 工作流 | `.ai/hooks/` / `.ai/ci/`（CI 会自动派生到 `.github/workflows/`） |

## 红线

与 `.ai/rules/ai-guidelines.md` 一致，重点重申：

- ❌ 禁止手改任何派生文件（改了会被 `--check` 拒绝并覆盖）。
- ❌ 禁止只同步部分工具。
- ❌ 个人本地配置（如 `.claude/settings.local.json`）不入 `.ai/`，直接放工具目录。

## 验收标准（全部满足才算完成）

1. `node .ai/sync.mjs --check` 输出「校验通过」且无孤儿残留；
2. 汇报包含：源改动清单、派生变化清单、校验输出原文；
3. 已提醒用户：源与派生结果需一起提交（钩子与 CI 会强制校验）。
