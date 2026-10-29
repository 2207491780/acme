# ACME

> 纯 AI Coding 项目模板 —— 一套「单源 + 派生」的多工具 AI 配置体系：所有 AI-Coding 内容只在 `.ai/` 一处维护，一条命令派生，国内外主流工具同时生效。

## 项目介绍

本模板解决多 AI Coding 工具共存的核心痛点：各家工具的配置路径与格式互不兼容（`CLAUDE.md`、`.cursor/rules/*.mdc`、`AGENTS.md`……），手工维护必然失同步。

方案是三条原则：

1. **唯一修改点** —— 所有 AI-Coding 相关内容（公共规范、分层规则、agents/skills/commands 公共池、工具专属配置、git 钩子、CI 工作流、派生引擎）只允许在 `.ai/` 下修改。
2. **其余皆引用** —— 仓库其他位置的 AI 相关文件全部是派生产物或对 `.ai/` 的直接消费：根部 4 个入口为引用存根（规范全文仅 `.ai/RULES.md` 一份）、工具目录由引擎生成、git 钩子被 `core.hooksPath` 直接指向、CI 由 `.ai/ci/` 派生。
3. **强制联动** —— 修改后必须运行 `node .ai/sync.mjs` 派生；`--check` 由 pre-commit 钩子与 CI 双重校验，任何漂移都会被拦截。

## 支持的厂商

| 厂商 | 工具 | 读取的配置入口 | 接入方式 |
|---|---|---|---|
| Anthropic | Claude Code | `CLAUDE.md` + `.claude/` | 专属目录 |
| Anysphere | Cursor | `.cursor/rules/*.mdc` | 专属目录 |
| GitHub（微软） | GitHub Copilot | `.github/copilot-instructions.md` | 专属目录 |
| OpenAI | Codex | `AGENTS.md` | 通用标准 |
| Google | Gemini CLI / Antigravity | `GEMINI.md` + `.gemini/` | 专属目录 |
| 腾讯 | CodeBuddy | `CODEBUDDY.md`（缺省时自动回退读 `AGENTS.md`） | 专属目录 |
| 字节跳动 | Trae | `.trae/rules/project_rules.md` | 专属目录 |
| 阿里巴巴 | Qoder | `.qoder/rules/project_rules.md`（含 `alwaysApply`，亦读 `AGENTS.md`） | 专属目录 |
| 智谱（Z.AI） | ZCode | `AGENTS.md`（官方确认） | 通用标准 |
| 心流 | iFlow CLI | `AGENTS.md` / `CLAUDE.md` + `.iflow/` | 生态兼容 |
| Sourcegraph / Block / OpenCode / Zed / 月之暗面 / AWS / JetBrains 等 | Amp、Goose、OpenCode、Zed、Kimi Code、Amazon Q、Junie CLI 等 | `AGENTS.md` | 通用标准 |

## 目录总览

```text
acme/
├── README.md / .gitignore / .gitattributes   # 项目介绍与工程配置（非 AI 内容）
├── AGENTS.md  CLAUDE.md  GEMINI.md  CODEBUDDY.md   # ⚙️ 引用存根 → .ai/RULES.md（全文仅此一份）
├── .ai/                          # ★★ AI 配置唯一事实源（结构见 .ai/README.md）
├── .ai-memory/                   # ★ 知识与记忆落盘（decisions 定稿 / playbooks 手册 / drafts 草稿）
├── .claude/ .cursor/ .gemini/ .iflow/ .trae/ .qoder/   # ⚙️ 全部为生成产物
├── .github/                      # ⚙️ Copilot 指令存根 + CI（全部生成）
└── docs/                         # 项目文档（设计/API 文档等，非 AI 配置）
```

## 快速开始

```bash
git clone <本模板> && cd acme
git init && git config core.hooksPath .ai/hooks     # 一次性初始化
# 修改任何 AI 配置：只改 .ai/ → node .ai/sync.mjs → 提交
```

## 文档导航

| 想了解 | 阅读 |
|---|---|
| **分工具使用教程**（11 类工具逐一说明 + 冒烟测试） | [.ai/TUTORIAL.md](.ai/TUTORIAL.md) |
| **让 AI 自维护**（21 条提示词案例 + 验收核对单） | [.ai/PROMPTS.md](.ai/PROMPTS.md) |
| **使用指南**（9 个实操案例、两种模式、故障排查） | [.ai/GUIDE.md](.ai/GUIDE.md) |
| 体系结构 / 派生一览 / 联动防线 / 新增工具 | [.ai/README.md](.ai/README.md) |
| AI 工具执行配置修改的五步流程 | [.ai-memory/playbooks/ai-config.md](.ai-memory/playbooks/ai-config.md) |
| 分层规则 / 工具专属配置 / 公共池格式 | `.ai/` 内各目录的 README.md |
