# 应用目录 AI 规则采用 scopes 派生

## 背景

`workspace/apps/` 下有真实业务代码：后端（Java 25 / Spring Cloud 多模块）、前端（vben v5 脚手架）。需要让 AI 进入具体应用目录时自动获得该目录的构建命令、约束与坑。候选做法：在应用目录内手写 `AGENTS.md`，或用 `.ai/scopes/` 派生。

## 决定

- 规则源统一放 `.ai/scopes/workspace/apps/{backend,frontend,frontend/web-admin}/AGENTS.md`，由引擎派生到对应项目根，并自动生成同级 `CLAUDE.md`。
- 应用内**禁止手工新增**其他 AI 配置；派生文件与根配置一样受 `--check` 管控。
- 上游脚手架（vben 的 `.github/`、`.docs/`、多语言 README）原样保留，不纳入本仓 AI 管理，不参与派生。
- 项目上下文（技术栈/命令）在 `.ai/rules/project.md` 精简版 + `.ai-memory/playbooks/dev-commands.md` 完整版，遵守 RULES ≤ 8KB 预算。

## 被否的选项

- **应用根手写 `AGENTS.md`**：多工具共用会漂移，且绕开 `--check`，与单源原则冲突。
- **把应用细节全塞进根规则**：超出 8KB 预算，且对无关任务形成噪音。
- **删除 vben 上游 `.github/.vscode/docs`**：属于第三方代码，删除会破坏脚手架升级路径；GitHub 只识别仓库根 `.github/`，无实际冲突。
