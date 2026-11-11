# apps/ — 应用代码

实际业务代码的唯一存放处。本仓库是「AI 配置体系 + 应用代码」双职责：`.ai/` 管 AI Coding 配置，`apps/` 管可运行的应用程序。

## 目录约定

```text
apps/
├── APPS.md          # 本文件：apps 层的约定说明与应用清单
├── backend/         # 后端服务（技术栈待确定）
├── frontend/        # 前端多端工程：web / desktop(Electron) / mobile
├── <app>/           # 一个可独立运行/部署的应用
│   ├── <app>.md     # 该应用的职责、启动方式、依赖说明（如 web-console.md）
│   └── ...
└── packages/        # （按需）跨应用共享的库
```

## 应用划分原则

- **一个目录一个应用**：以「能否独立部署」为界。能独立部署的（前端站点、后端服务、CLI、桌面端）各自一个目录；只被其他应用引用的代码进 `packages/`。
- **命名**：目录名用 kebab-case，与应用包名保持一致（如 `apps/web-console`）。
- **自洽性**：每个应用目录内放一份与目录同名的说明文件（如 `web-console.md`），写清「做什么、怎么跑、依赖什么」。外部 AI 工具进入该目录时只读它即可上手。
- **禁止**在应用目录内放置 AI 工具的配置文件（如 `CLAUDE.md`、`.cursor/rules/`）。AI 规范全局统一，由根目录 `.ai/` 派生，详见[根 README](../../README.md)。

## 应用清单

| 应用 | 职责 | 说明 | 当前状态 |
|---|---|---|---|
| [`backend/`](backend/backend.md) | 后端服务 | [backend.md](backend/backend.md) | 空骨架，技术栈待确定 |
| [`frontend/`](frontend/frontend.md) | 前端多端工程：Web、Electron PC、移动端 | [frontend.md](frontend/frontend.md) | 已实现可运行的多端交付台（React + Vite + Ant Design / Electron / React Native + Expo，共享 `@acme/core`） |

`backend/` 仍为空骨架，技术栈待确定。`frontend/` 为 pnpm monorepo，已可运行：在 `frontend/` 执行 `pnpm install` 后，Web 端 `pnpm dev`，桌面端 `pnpm desktop:dev`，移动端 `pnpm mobile:start`（需模拟器或 Expo Go 设备）。多端视觉一致性由 `@acme/core` 设计令牌单一事实源保证，实施契约见 [`frontend/UI-SPEC.md`](frontend/UI-SPEC.md)，跨端校验页 `pnpm ui:guide`。详见各子工程说明。
