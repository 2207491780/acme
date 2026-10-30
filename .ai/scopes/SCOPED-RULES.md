# .ai/scopes/ — 分层规则源（多工具分发）

遵循 AGENTS.md 开放标准的分层实践：仓库任意目录可有自己的规则，兼容工具按「从宽到具体」就近加载，与根规范叠加生效。

## 源与产物

源放本目录，**路径 = 仓库内相对路径**：

```text
.ai/scopes/workspace/apps/backend/AGENTS.md  → workspace/apps/backend/AGENTS.md
```

每个 `AGENTS.md` 由引擎同时产出 5 种形态，覆盖不同工具的加载机制：

| 产物 | 服务工具 | 生效方式 |
|---|---|---|
| `<dir>/AGENTS.md` | Codex、Cursor、ZCode 等 AGENTS.md 标准族 | 目录就近加载 |
| `<dir>/CLAUDE.md` | Claude Code | `@AGENTS.md` 导入；进入该目录时加载（`--links` 模式下为符号链接） |
| `<dir>/GEMINI.md` | Gemini CLI / Antigravity | `@AGENTS.md` 导入；层级记忆 |
| `.cursor/rules/scope-<slug>.mdc` | Cursor | `globs: ["<dir>/**"]` + `alwaysApply: false`，触及该目录文件时自动附加 |
| `.github/instructions/scope-<slug>.instructions.md` | GitHub Copilot | `applyTo: "<dir>/**"` 路径匹配生效 |

## 覆盖边界

- **根级工具**（Trae / Qoder / CodeBuddy / iFlow 等无目录级规则机制）：只读根入口，应用级差异**不会**自动加载——这类工具在应用内工作时，应主动读取该目录的 `AGENTS.md`（根规则「动手之前」已要求先读上下文）。
- 各形态均由 `--check` 逐字节校验；删除 scope 后残留会被孤儿检测报出。

## 维护

- 改源 → `node .ai/sync.mjs` → `--check`；分层内容建议短小聚焦（目录职责、局部约定、命令差异），公共规范仍放 `.ai/rules/`。
- 新增作用域：在 `.ai/scopes/<仓库相对路径>/AGENTS.md` 新建（**无需改脚本**），5 种产物自动生成。
