# 前端测试引擎二期极限提速（esbuild 测试期依赖预打包 + LPT 权重分片 + 并发收敛 + 冗余 jsdom 清理）

> 在 `2026-11-27-01-zero-docker-fast-test-architecture.md`（一期：前端全量 `87.8s` / `@acme/web 79.5s`）基础上继续重构前端自动化测试引擎；全程零 Docker 依赖、零新增 npm 依赖、100% 保留 jsdom + AntD 真实组件行为语义。

## 背景

一期完成后，前端单测全量 `CI=true pnpm test` 降至 `87.8s`（`@acme/web` 98 文件 `79.5s`），且近期业务迭代后 `@acme/web` 增至 **101 文件 / 790 用例**。实测剖析剩余耗时瓶颈如下：

1. **巨型依赖每次启动重复解析与 Vite SSR 转换**：
   - `antd`（~2,870ms）、`@ant-design/icons`（831 个图标 ~2,192ms，含 AntD 内部 154 个直引路径）、`lucide-react`（~2,000ms）、`@antv/x6`（~705ms）在隔离池每个 fork 与每个共享分片首启时都会触发大量 NTFS 文件 stat/require 与 Vite SSR transform；
   - `antd` 默认 dev 模式在单测中输出超过 380KB 的 `Warning: [antd: Modal] destroyOnClose is deprecated` 栈追踪刷屏，白耗序列化与 I/O。
2. **分片不均与并发过载**：
   - 原轮询均分未考虑重型页面测试（`preferencesBackendSync` 7.5s、`tabbar-scroll-preview` 6.5s、`schedulerAdminPage` 5.0s、`deptsPage` 4.8s），导致尾分片拖慢全量墙钟；
   - 13 个进入共享 DOM 分片的 `.test.tsx` 文件首行带有冗余的 `/** @vitest-environment jsdom */` 或 `// @vitest-environment jsdom` 声明，覆盖了 `shared-jsdom-env.ts` 配置并强制在 `singleFork` 中途销毁重建 13 次 jsdom；
   - `@acme/mobile` 缺省无上限 fork 与 `@acme/web` 同批启动争抢 CPU；`@acme/core`（32 文件仅 1.5s 纯测试）采用默认 `forks` 池带来显著 Windows 进程创建开销（`prepare ~13.8s`）。
3. **测试文件内串行超时兜底空等**：
   - `REQ20261120214653-permissionsPage.test.tsx` 等 4 个页面测试使用串行 `try { await findBy*(..., { timeout: 2000/3000 }) } catch {}` 回退探测控件，单文件空等高达 15.5s。

## 决定

### 1. 零新增依赖的测试期 esbuild 预打包（`web/scripts/prebundle-test-deps.mjs`）

- 复用 Vite 内置 `esbuild`，按包版本与 `BUNDLE_SCHEMA_VERSION` 哈希缓存在 `web/node_modules/.cache/acme-test-deps/`（首次构建 ~1.45s，后续命中缓存 0ms）：
  - `antd.cjs`：单文件 CJS 预打包 `antd` + `rc-*` + `@ant-design/cssinjs`（`external: ['react', 'react-dom', 'react/jsx-runtime', 'react/jsx-dev-runtime', 'dayjs', 'dayjs/*']`，避免 `dayjs` 双副本破坏 `DatePanel.weekday`），构建期插件直接 NOOP `useStyleRegister`/`useCSSVarRegister`、将 AntD 内部 154 个 `@ant-design/icons/*` 替换为保留 `.anticon.anticon-<kebab>` 与 `<svg data-icon>` 的轻量桩，并仅在 `antd.cjs` 内部 `define: { 'process.env.NODE_ENV': '"production"' }` + `minifySyntax: true` 静默 AntD 废弃告警刷屏（不动 React 自身 `NODE_ENV`）。实测加载从 **2,870ms → 292ms（9.8x）**；
  - `antd-icons.cjs` & `lucide-react.cjs`：扫描包导出生成单文件静态 CJS 桩（非 `.mjs`，彻底绕开 Vite SSR ESM transform），加载分别降至 **7.0ms** 与 **26.8ms**；
  - `antv-x6.cjs`：单文件 CJS 预打包 `@antv/x6`，加载从 **705ms → 80.4ms（8.8x）**；
- `web/vitest.workspace.ts` 仅精确别名包根（`/^antd$/` 等，不伤 `antd/locale/zh_CN` 等子路径），并通过 `server.deps.external: [/acme-test-deps[\\/]/]` 让 Vitest 直接走 Node 原生 `require()` 加载预打包 CJS，消除每个 Vite 进程 10~14s 的重复 `transform`。

### 2. LPT 权重均衡 5 分片 + 硬件匹配并发收敛

- `web/vitest.workspace.ts` 以实测单文件耗时为权重执行 LPT（Longest Processing Time first）贪心装箱，缺省切分 **5 个** `shared-jsdom` `singleFork` 分片（支持 `ACME_WEB_SHARDS` 覆盖），同步收敛 `web-dom-isolated`（`maxForks: 3`）与 `web-node-isolated`（`maxForks: 2`），使总活跃进程匹配 12 核/16 线程物理硬件，消除多 Vite 进程争抢 P 核导致的 2x 抖动；
- 移除 13 个共享分片 `.test.tsx` 首行冗余的 `@vitest-environment jsdom` 注释（`vitest.config.ts` 与 `vitest.workspace.ts` 已为全部 `.test.tsx` 默认配置 jsdom/shared-jsdom，单文件直跑同样生效），各分片 `environment` 开销由 ~3.3s 降至 ~1.8s；
- `web/tests/setup.ts` 补齐 `HTMLCanvasElement.prototype.getContext` 可 `spyOn` 桩（消除 Watermark 栈追踪刷屏）、CJS `Module._load` 图标回退钩子，以及 `afterAll` 对 `@acme/core` 全部 8 个全局 store + `useTabsStore` + `useStore` 的完整重置；
- `packages/core/vitest.config.ts` 切至 `pool: 'threads'`（`minThreads: 2, maxThreads: 6`），减少 Windows 子进程 fork 开销；`mobile/vitest.config.ts` 收敛 `maxForks: 4`，避免与 `@acme/web` 并发启动期争抢 CPU。

### 3. 消除 4 份测试中的串行超时兜底等待

- 将 `permissionsPage`、`crudPages`、`loginSecurityPages`、`systemConfigPage` 中串行 `try { findBy*(..., { timeout: 2000/3000 }) } catch {}` 改为单次 `waitFor` 组合 `queryAllBy*` 同步探测，保持断言语义 100% 等价（`permissionsPage.test.tsx` 由 15.5s 降至 1.7s）。

## 实测效果

| 范围 | 原始基线 | 一期（`0c91f89`） | 二期（本决策） | 相对一期 / 原始提速 |
|---|---|---|---|---|
| 前端全量 `CI=true pnpm test`（4 包 176 文件 / 1,180 用例，0 跳过） | 215s ~ 735s | 87.8s | **49.8s** | **1.76x / 4.3x ~ 14.8x** |
| `@acme/web` 全量（101 文件 / 790 用例） | 197s ~ 718s | 79.5s（98 文件） | **40.1s**（101 文件） | **1.98x / 4.9x ~ 17.9x** |
| `@acme/web` 单文件定向透传（`tenants.test.tsx`） | ~12s+ | ~9s | **6.0s**（含 pnpm 包装 7.1s） | — |
| `web-dom-1..5` 单分片均值 / 跨度 | — | 不均衡 | **34.0s ~ 38.1s**（高度均衡） | — |
| 其余门禁（`typecheck` / `desktop check` / `build`） | 全通过 | 全通过 | **全通过**（`web build` 13.9s） | 零回退 |

## 被否的选项

- **迁移 `happy-dom` 替代 `jsdom`**：**实测否决**。在 `happy-dom@15` 原型验证中遇到两类底层兼容阻断：(1) 缺失 `window.WebSocket` 导致 `NotificationBell` 在 React commit 期抛异常并污染后续渲染；(2) 即使补桩 `WebSocket`，`happy-dom` 仍导致 `packagesPage` Popconfirm 可见性断言失败，并在 4/6 DOM 分片及隔离池的复杂 AntD 交互测试中出现 50s+ 无限挂起。已完整移除 `happy-dom` 依赖与原型文件，坚持 100% 兼容的 `jsdom` + 持久化 `shared-jsdom-env`。
- **单进程 Vitest 跑多 `singleFork` workspace 项目**：**实测否决**。Vitest 2.1.9 对同进程内多个 `singleFork: true` workspace 项目仍按串行执行（实测全量 `150.7s`），必须通过 `scripts/run-tests.mjs` 并发调度各分片。
- **在 `@acme/web` 使用 `pool: 'threads'` 或盲目提高分片数至 7+**：**实测否决**。`threads` 在 Windows 下因主线程与 worker 共享 libuv/V8 竞争反而慢 10~15s；7 分片（9 个 Vite 进程 + 16 worker）叠加 `@acme/mobile` 导致 16 线程主机发生 P 核 thrashing（58.1s vs 5 分片 40.1s）。
