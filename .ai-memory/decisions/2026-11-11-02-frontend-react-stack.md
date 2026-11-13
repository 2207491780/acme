# 前端全栈敲定：React + Ant Design + React Native（取代 vanilla 方案）

日期：2026-11-11
取代：`2026-11-11-01-frontend-stack.md`（原记录已按 2026-11-13-15 删除，vanilla 方案要点见下方背景）

## 背景

产品定位由「示例/模板」明确为**长期演进的真实产品**，需分别确定 Web / PC / 移动三端方案。原 vanilla ES Modules 方案零依赖、易审计，但缺组件模型、类型安全与生态，难以支撑长期多人协作与复杂交互。用户拍板：**移动端 React Native，UI 库 Ant Design**。

## 决定

统一「一套 React + TS 底座，最大化共享业务层，各端只做壳」：

- **monorepo**：pnpm workspaces，根在 `workspace/apps/frontend`；`packages/core` 放框架无关 TS（类型 / 种子数据 / reducer / 筛选排序 / 指标），三端共享。
- **Web**：React 18 + Vite 5 + TypeScript + Ant Design 5 + React Router 6；状态 Zustand（客户端）+ TanStack Query（服务端，预留）；测试 Vitest。
- **PC**：Electron ^37 复用 Web 的 React 渲染层（electron-vite 构建）；壳层只管窗口 / IPC / 自动更新；保留 contextIsolation、CSP、`acme://` 生产协议。
- **移动**：React Native + Expo，UI 用 antd-mobile，共享 `core` 业务层与类型；原生 UI 独立。
- 现有 vanilla 交付台的已验证业务逻辑与交互**迁移**到 React + AntD，不推倒业务模型。

## 被否的选项

- **保持 vanilla ES Modules**：长期产品扩展性、类型安全、生态不足，被用户否决。
- **移动端 Capacitor 套壳 Web**：复用最大化但 webview 性能与原生手感有天花板；用户选 React Native 取原生上限。
- **移动端 Flutter**：需另学 Dart，无法复用 React/TS 心智与 core；否决。
- **PC 用 Tauri**：更轻但引入 Rust 工具链，团队以 JS/TS 为主，Electron 复用 Web 更稳；暂否决（保留为后续优化项）。
- **Web 用 Next.js**：当前为控制台型 SPA，无 SSR/SEO 刚需，Vite 更简；若后续需要 SSR 再迁移。