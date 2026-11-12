# 前端多端技术栈与桌面产物加载方式

日期：2026-11-11

## 背景

仓库是「AI 配置体系 + 应用代码」双职责模板，`workspace/apps/frontend/` 需落地 Web / PC(Electron) / 移动三端，且 PC 端套壳 Web 端。约束：

- `.ai/rules/` 依赖红线：不得引入未在 `.ai/rules/project.md` 登记的框架/库；当时技术栈一栏为 TODO，无任何已登记 Web 框架。
- `.ai/RULES.md` 拼接预算 ≤ 8KB，规范须精简。
- 需要「可运行、可测试、可浏览器验证」的真实交付，而非骨架。
- 三端须共享业务层，避免多套界面/逻辑漂移。

## 决定

- Web 业务层用**原生 JavaScript ES Modules**，零运行时第三方依赖；服务器、构建、语法检查均用原生 Node 脚本，测试用 Node 内置 `node:test`。仅在 `project.md` 登记 Node ≥20 与 Electron。
- **移动端**入口 `mobile/index.html` 直接加载 `web/src/main.js`，共享业务层与 localStorage，响应式断点适配小屏。
- **桌面端** Electron 套壳 Web：开发 `loadURL(http://localhost:5173/web/)`；生产注册 `acme://` privileged 协议（`standard/secure/supportFetchAPI/corsEnabled`）从 `web/dist` 取文件，`loadURL(acme://app/web/index.html)`。
- 桌面安全：`contextIsolation:true` + `nodeIntegration:false`，预加载用 CommonJS `.cjs` 暴露最小 `window.acmeDesktop`；外部 URL 经 `isSafeExternalUrl` 仅放行 http/https；页面加 CSP（`script-src 'self'`）。

## 被否的选项

- **React/Vue 等框架**：违反依赖红线（未登记），且引入构建链与额外依赖，违背「最简可靠」与精简预算。
- **生产用 `loadFile(file://)`**：Chromium 下 ES Module 无法经 `file://` 加载（CORS  opaque origin），会导致桌面生产白屏——实测验证后改用自定义协议。
- **生产桌面内嵌本地 HTTP 服务器**：`serve.mjs` 默认绑定 0.0.0.0，会暴露局域网端口；自定义协议无网络暴露，更安全。
- **移动端复制一份业务逻辑**：与 Web 端形成两套代码，必然漂移；改为共享 `main.js`。