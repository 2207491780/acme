# frontend — 前端多端工程

前端 pnpm monorepo，包含共享业务层与 Web / PC 桌面 / 移动三个子工程，实现「ACME 多端前端交付台」（发布单管理控制台）。技术栈：TypeScript 5 / Node ≥22.13 / pnpm 11.8（与根 `.ai/rules/project.md` 及 `package.json` engines 一致）；Web 用 React 18 + Vite 5 + Ant Design 5，PC 用 Electron 套壳 Web 产物，移动端用 React Native + Expo，三端共享框架无关的 `@acme/core` 业务层。

## 多端定位

| 子工程 | 目录 | 形态 | 说明 |
|---|---|---|---|
| 共享层 | [`packages/core/`](packages/core) | 框架无关 TS | 类型 / 常量 / 工具 / 种子 / reducer / 选择器 / **设计令牌**，三端唯一业务与视觉来源 |
| Web 端 | [`web/`](web/WEB.md) | React SPA | 页面与 UI 唯一来源（React + Vite + Ant Design + Zustand） |
| PC 端 | [`desktop/`](desktop/DESKTOP.md) | Electron 桌面应用 | 套壳 Web 产物，不维护第二套页面源码 |
| 移动端 | [`mobile/`](mobile/MOBILE.md) | React Native + Expo | 共享 `@acme/core`，Ant Design 风格的原生组件 |

## 目录结构

```text
frontend/
├── FRONTEND.md
├── UI-SPEC.md              # 面向 AI 的多端一致 UI 实施契约（令牌/组件/布局/无障碍）
├── package.json            # @acme/frontend，workspace 根脚本
├── pnpm-workspace.yaml     # packages/* + web + desktop + mobile
├── tsconfig.base.json      # 共享 TS 编译选项
├── scripts/                # build-style-guide.mjs + style-guide.template.html
├── ui-spec/                # 生成的跨端校验页 style-guide.html（pnpm ui:guide）
├── packages/core/          # @acme/core：共享类型/业务/状态/设计令牌（框架无关 TS）
│   ├── src/                # types / constants / utils / theme / seed / reducer / selectors / index
│   └── tests/              # reducer / utils / theme.test.ts（Vitest）
├── web/                    # @acme/web：React + Vite + Ant Design
│   ├── src/                # App / main / theme / components / pages / store / hooks / utils / styles
│   └── tests/              # store.test.ts / theme.test.ts（Vitest）
├── desktop/                # @acme/desktop：Electron 壳层
│   ├── src/main/           # 窗口、单实例锁、IPC、导航拦截、acme:// 协议注册
│   ├── src/preload/        # contextBridge 暴露 window.acmeDesktop
│   ├── src/shared/         # security.js / app-protocol.js
│   ├── scripts/            # dev.mjs / start.mjs
│   └── tests/              # dev / sandbox / security.test.mjs（node:test）
└── mobile/                 # @acme/mobile：React Native + Expo
    ├── App.tsx / index.ts  # 根组件与注册入口
    ├── src/                # screens / components / store / hooks / theme / nav / utils
    └── tests/              # store.test.ts / theme.test.ts（Vitest）
```

## 复用边界

- 业务逻辑只放在 `packages/core/`，三端通过源码别名直接复用（Vite alias / tsconfig paths / Metro `extraNodeModules` → `packages/core/src`），无需先构建 dist；`core` 同时产出 dist 供 Node 侧消费。
- Web 页面源码只放在 `web/`；PC 端复用 Web 产物：开发加载 Vite Dev Server（http），生产通过自定义 `acme://` 协议加载 `web/dist`（ES Module 安全，无网络端口暴露）。
- 移动端复用 `core` 业务层与 Zustand store 模式，UI 为 RN 原生组件（Ant Design 风格），不复用 Web 的 DOM 代码，也不复用 Electron 主进程/预加载。
- `desktop/src/main/` 只放 Electron 主进程能力；`src/preload/` 只放最小受控桥接，页面不直接访问 Node.js。
- 真实凭证不写入前端代码；环境搭建遵循 [`ENV-STACK.md`](../../env-stack/ENV-STACK.md) 与 [`.ai/env/ENV-GUIDE.md`](../../../.ai/env/ENV-GUIDE.md)。

## 设计令牌与 UI 规范（多端一致）

- **唯一事实源**：[`packages/core/src/theme.ts`](packages/core/src/theme.ts)。强调色 / 语义色 / 中性色 / 间距 / 圆角 / 字体 / 动效 / 渐变全部在此定义，经 `@acme/core` 导出。
- **派生**：Web/PC 的 [`web/src/theme.ts`](web/src/theme.ts) `buildTheme()` 与 Mobile 的 [`mobile/src/theme.ts`](mobile/src/theme.ts) 均程序派生自 core；组件不得另行硬编码令牌值。Web 语义标签用 `*_ANTD` 预设名（AntD 算法渲染），Mobile 用 `TONES`/`*_COLORS` hex（语义一致、渲染 idiomatic）。
- **实施契约**：[`UI-SPEC.md`](UI-SPEC.md) 面向 AI，含令牌表、core→AntD→RN 映射、组件契约、布局/安全区、动效、无障碍与实施清单。
- **跨端校验页**：`pnpm ui:guide` 由 core 令牌生成 [`ui-spec/style-guide.html`](ui-spec/style-guide.html)，Web(AntD 风格) 与 Mobile(RN 精确复刻) 标本并排，控制条实时切换强调色/主题/密度/视图/圆角/间距/动效，用于核对两端一致性。
- **不回归锁定**：`web/tests/theme.test.ts` 与 `mobile/tests/theme.test.ts` 锁定派生输出，改令牌须同步更新并跑校验页。

## 常用命令（相对 `frontend/`，使用 pnpm）

| 用途 | 命令 |
|---|---|
| 安装依赖 | `pnpm install` |
| Web 开发 | `pnpm dev`（= `pnpm --filter @acme/web dev`，http://localhost:5173/） |
| Web 构建 | `pnpm --filter @acme/web build` |
| 桌面开发 | `pnpm desktop:dev`（自动起 Vite 并打开 Electron 窗口） |
| 桌面生产 | `pnpm --filter @acme/desktop start`（构建 Web 后加载 acme://dist） |
| 移动端 | `pnpm mobile:start`（Expo，需模拟器或 Expo Go 设备） |
| 全量类型检查 | `pnpm typecheck` |
| 全量测试 | `pnpm test` |
| 全量构建 | `pnpm build` |
| 生成跨端 UI 校验页 | `pnpm ui:guide`（输出 `ui-spec/style-guide.html`） |

从仓库根执行时前缀为 `workspace/apps/frontend/`。AI 配置统一维护于根目录 `.ai/`，不在前端子工程内放置工具专属配置。