# 项目上下文

## 当前实现

ACME 是多端发布流程演示台，不是真实部署平台：发布单、审批、构建进度和产物均为本地模拟，没有后端鉴权或部署接口。Web 使用本地存储，移动端目前仅内存存储。

- 工程根 `workspace/apps/frontend/`；`packages/core/` 为框架无关业务/令牌，`web/` 为 React UI，`desktop/` 复用 Web 产物，`mobile/` 为 React Native。
- 实际栈：Node ≥22.13、pnpm 11.8、TypeScript 5、React 18、Vite 5、Ant Design 5、Zustand、Electron 37、Expo 52 / RN 0.76；测试为 Vitest 与 Node test。
- 未接入 TanStack Query、antd-mobile、electron-vite，不能假定这些依赖存在。
- 视觉事实源为 `packages/core/src/theme.ts`；UI 修改先读工程内 `UI-SPEC.md`，校验页用 `pnpm ui:guide` 生成，禁止手改生成页。

## 关键回归

外部数据加载/导入需验证实体、设置及关联；非法输入不得覆盖有效状态。删除撤销不能因 ID 复用丢记录，切换筛选不能误操作隐藏选项；发布目标变化不能沿用旧审批和产物。多窗口修改需检查存储冲突。

## 命令与门禁

在工程根运行 `pnpm typecheck`、`pnpm test`、`pnpm build`；入口为 `pnpm dev`、`pnpm desktop:dev`、`pnpm mobile:start`。桌面额外运行 `pnpm --filter @acme/desktop check`。

AI 修改运行 `node .ai/sync.mjs` 和 `node .ai/sync.mjs --check`。同步通过不代表钩子已启用；派生引擎回归测试在 `.ai/tests/`（CI ai-config 作业运行）。引擎开关见 `.ai/ai-coding-config.yml`。

环境连接定义在 `.ai/env/`，搭建脚本在 `workspace/env-stack/`；prod 默认只读，禁止明文密钥。
