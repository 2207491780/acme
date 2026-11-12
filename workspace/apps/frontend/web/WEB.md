# web — Web 端

浏览器端 React SPA，也是 PC 桌面端的页面/产物来源。实现「ACME 多端前端交付台」：发布单的创建、编辑、删除、搜索、筛选、排序、拖拽调序、批量操作、保存视图、撤销、详情抽屉与状态流转（草稿→构建→测试→审批→发布），数据持久化于 localStorage。

## 技术栈

React 18 + Vite 5 + React Router 6（HashRouter SPA）+ Ant Design 5 + Zustand 5；TypeScript 5；Vitest 单测。业务逻辑全部来自共享 `@acme/core`（源码别名复用，不复制）。

## 目录结构

```text
web/
├── WEB.md
├── index.html              # Vite 入口外壳
├── package.json            # @acme/web
├── vite.config.ts          # @acme/core → ../packages/core/src 别名
├── vitest.config.ts
├── tsconfig.json
├── public/                 # manifest.webmanifest、icon.svg
├── src/
│   ├── main.tsx            # 挂载根组件
│   ├── App.tsx             # ConfigProvider + 路由 + 布局
│   ├── theme.ts            # Ant Design 主题令牌 / 强调色
│   ├── store/useStore.ts   # Zustand 包裹 core reducer + 持久化 + UI 态
│   ├── hooks/useReleaseActions.tsx
│   ├── components/         # atoms / PageHead / ReleaseDrawer / ReleaseFormModal / AppFormModal / SaveViewModal
│   ├── pages/              # Dashboard / Releases / Apps / Settings
│   ├── utils/dom.ts        # 存储适配器、复制、下载、平台探测
│   └── styles/global.css   # 设计系统：背景/滚动条/响应式
└── tests/                  # Vitest：store（22 用例）/ theme（6 用例）
```

## 常用命令（相对 `web/`）

| 用途 | 命令 |
|---|---|
| 本地运行 | `pnpm dev`（Vite，http://localhost:5173/） |
| 构建 | `pnpm build`（先 `tsc --noEmit` 再 `vite build`，输出 `dist/`） |
| 预览产物 | `pnpm preview` |
| 类型检查 | `pnpm typecheck` |
| 测试 | `pnpm test` |

## 与其他端的关系

- 桌面开发：Electron 加载本工程 Vite Dev Server（http://localhost:5173/）。
- 桌面生产：Electron 通过 `acme://` 自定义协议加载本工程 `dist/index.html`。
- 移动端：不复用 Web DOM 代码，仅共享 `@acme/core` 业务层。
- 桌面专属能力通过预加载桥接（`window.acmeDesktop`）暴露，不写入通用 Web 逻辑。

## 依赖与安全

依赖均在 `.ai/rules/project.md` 登记（React / Vite / Ant Design / React Router / Zustand）。所有用户内容经 React 默认转义渲染；真实凭证不写入本目录，后端接口地址与约定待确定。