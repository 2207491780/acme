# desktop — PC 桌面端

基于 Electron 的 PC 桌面应用，套壳 Web 端实现界面。主进程负责窗口管理、受控预加载桥接、安全 IPC 与自定义生产协议；界面与业务统一来自 [`../web/`](../web/WEB.md) 的 React 产物，不维护第二套页面源码。

## 技术栈

Electron ^37（开发依赖）；主进程为 ES Module，预加载为 CommonJS（`.cjs`）；壳层脚本为原生 Node（`.mjs`），不引入额外打包器。

## 目录结构

```text
desktop/
├── DESKTOP.md
├── package.json            # @acme/desktop，main = src/main/index.js
├── src/
│   ├── main/index.js       # 窗口、单实例锁、IPC、导航/开窗拦截、生产协议注册
│   ├── preload/index.cjs   # contextBridge 暴露 window.acmeDesktop 最小 API
│   └── shared/
│       ├── security.js     # isSafeExternalUrl（仅允许 http/https）
│       └── app-protocol.js # acme:// privileged 协议，服务 web/dist 静态资源（显式 MIME）
├── scripts/
│   ├── dev.mjs             # 起 Vite Dev Server + 启动 Electron（加载 http）
│   └── start.mjs           # 构建 Web + 启动 Electron（加载 acme://dist）
└── tests/                  # node:test 回归
    ├── dev.test.mjs        # dev.mjs 启动/清理生命周期（不启动真实窗口）
    ├── sandbox.test.mjs    # 主进程沙箱配置与预加载桥接契约
    └── security.test.mjs   # 外部 URL 安全校验
```

## Web 产物接入

- 开发模式：`dev.mjs` 启动 Web 的 Vite Dev Server，主进程 `loadURL(http://localhost:5173/)`。
- 生产模式：`start.mjs` 先 `vite build`，主进程注册 `acme://` 协议并 `loadURL(acme://app/index.html)`，由 `app-protocol.js` 从 `../web/dist` 取文件。
- 采用自定义 privileged 协议而非 `file://`：Chromium 下 ES Module 无法经 `file://` 加载（CORS），且协议处理器为模块脚本显式设置 `Content-Type`，避免被拒绝；同时不暴露本地网络端口。
- 壳层脚本通过 `vite/package.json` 的 `bin` 字段解析 Vite 可执行文件（Vite 5 的 `exports` 不暴露 `./bin/vite.js`）。
- 不创建 `renderer/` 页面源码目录；桌面端无独立构建产物，渲染层由 Web 构建。

## 安全边界

- `contextIsolation: true`、`nodeIntegration: false`；页面仅通过 `window.acmeDesktop` 访问受控能力（`platform`、`versions`、`openExternal`、`getInfo`）。
- `openExternal` 与开窗/导航均经 `isSafeExternalUrl` 校验，拒绝 `file:`/`javascript:`/`data:` 等危险协议。
- 真实凭证不写入前端代码或桌面配置。

## 常用命令（相对 `desktop/`）

| 用途 | 命令 |
|---|---|
| 安装依赖 | `pnpm install`（在 `frontend/` 根执行；Electron 二进制由 allowBuilds 放行） |
| 开发运行 | `pnpm dev`（自动起 Vite 并打开窗口） |
| 生产启动 | `pnpm start`（构建 Web 后加载 acme://dist） |
| 测试 | `pnpm test` |
| 代码检查 | `pnpm check`（对主进程/预加载/共享/脚本逐文件 `node --check`） |