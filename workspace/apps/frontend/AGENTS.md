# ACME 前端

TypeScript 5 / React 18 多端 pnpm monorepo（Node ≥22.13、pnpm 11.8）：`packages/core`（@acme/core，框架无关
业务/状态/设计令牌）、`web`（React + Vite + Ant Design 5）、`desktop`（Electron 套壳 Web 产物）、`mobile`
（React Native + Expo）。根 `AGENTS.md`（由 `.ai/RULES.md` 派生）始终生效，本文件只做前端补充。

## 约定（详细口径见文档）

- **改界面前先读同目录 `UI-SPEC.md`**（多端一致的可执行设计契约）。视觉唯一事实源 `packages/core/src/theme.ts`：
  色值/尺寸/字体/动效一律从 `@acme/core` 令牌取，禁硬编码、禁第二套强调色、禁霓虹/光晕/彩虹渐变。
- **复用边界**：业务逻辑只放 `packages/core/`，三端经源码别名复用（不复制）；Web 页面源码只在 `web/`，
  PC 复用 Web 产物（不维护第二套页面），Mobile 用 RN 原生组件（不复用 DOM/AntD，图标纯 View 绘制）。
- **改令牌后**跑 `pnpm -r test && pnpm ui:guide`，在校验页核对 Web/Mobile 两栏；`web/tests/theme.test.ts`
  与 `mobile/tests/theme.test.ts` 锁定派生输出，不得绕过。
- **测试与门禁**（相对 `frontend/`，用 pnpm）：core/web/mobile 用 Vitest、desktop 用 node:test；
  门禁 `pnpm typecheck`、`pnpm test`、`pnpm build`，桌面额外 `pnpm --filter @acme/desktop check`；
  入口 `pnpm dev`（Web）/ `pnpm desktop:dev` / `pnpm mobile:start`。
- 技术栈取舍见 `.ai-memory/decisions/2026-11-11-02-frontend-react-stack.md`、
  `2026-11-12-01-mobile-ui-native-rn.md`；命名见 `.ai-memory/playbooks/naming.md`；总览见同目录 `FRONTEND.md`。

<!-- 派生自 .ai/scopes/workspace/apps/frontend/AGENTS.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
