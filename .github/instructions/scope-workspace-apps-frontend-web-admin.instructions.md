---
applyTo: "workspace/apps/frontend/web-admin/**"
---

# web-admin 目录规范（vben v5 脚手架）

中后台前端项目，基于 **Vue Vben Admin v5**（Vue 3 + Vite + TypeScript，pnpm + turbo 单仓）。

## 运行时与命令

- 运行时：Node **24.16.0**（见 `.node-version`）、pnpm **11.16.0**（`packageManager` 锁定）；要求 Node ≥22.18/24.12，pnpm ≥11。

```bash
pnpm install            # 安装依赖（锁定 pnpm-lock.yaml）
pnpm dev                # 本地开发
pnpm run check          # 循环依赖 + 依赖检查 + 类型检查 + 拼写
pnpm run test:unit      # vitest 单测
pnpm run build          # 生产构建（turbo）
pnpm run lint           # 校验（vsh lint，含 oxlint/oxfmt/stylelint）
```

## 结构

- `apps/*`：可独立运行的应用（`web-antd` / `web-antd-next` / `web-ele` / `web-naive` / `web-tdesign` / `backend-mock`）。
- `packages/*`：共享包（`@core`、`effects`、`stores`、`utils`、`locales`、`styles` 等）。
- `internal/*`：内部工程配置与脚本；`playground/` 为演示。

## 约束

- **只改业务相关**：上游脚手架代码（`internal/*`、构建配置、`.github/`、`.vscode/`、`lefthook.yml`、eslint/oxlint/stylelint 配置）非必要不改动；确需动，先在 PR 说明理由。
- `.github/`、`.vscode/`、`docs/`、多语言 README 为**上游仓库遗留**，不参与本仓库流程（GitHub 只识别仓库根 `.github/`）；不要把 AI 工具配置放进来。
- 代码风格以 `pnpm run lint` 为准，禁止手工调整格式。
- 提交信息沿用根 `.ai/rules/git-workflow.md`（Conventional Commits，与上游 commitlint 兼容）。
- 子应用新增/升级依赖前说明理由（前端版本锁定在 pnpm-lock.yaml）。

<!-- 派生自 .ai/scopes/workspace/apps/frontend/web-admin/AGENTS.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
