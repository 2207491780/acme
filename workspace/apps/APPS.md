# apps/ — 应用代码

实际业务代码的唯一存放处。AI 规范统一由仓库根 `.ai/` 派生（本目录各项目根会有派生出的 `AGENTS.md` / `CLAUDE.md`），**禁止手工新增其他 AI 配置文件**。

## 应用清单

| 目录 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [backend/](backend/BACKEND.md) | 后端 | ✅ 活跃 | Java 25 + Spring Boot 4.1.1 + Spring Cloud 2025.1.3，Maven 多模块；规范见 `backend/doc/Backend.md` |
| [frontend/](frontend/FRONTEND.md) | 前端 | ✅ 活跃 | 多项目：`web-admin`（vben v5）活跃；data-visual / flutter-app / wechat-mini 占位 |

## 约定

- **一个目录一个可部署应用**；共享库进 `packages/`（按需）。
- 每个项目根放一份实义命名的工程说明（`backend/BACKEND.md`、`frontend/FRONTEND.md`），**不使用 README.md**（全库仅根目录保留 README.md）。
- 每个项目根的 AI 规则（`AGENTS.md` / `CLAUDE.md` / `GEMINI.md`）由 `.ai/scopes/workspace/apps/**/AGENTS.md` 派生；Cursor / Copilot 分别额外生成 `.cursor/rules/scope-*.mdc`、`.github/instructions/scope-*.instructions.md`。
- 命名用 kebab-case，与应用包名一致。
