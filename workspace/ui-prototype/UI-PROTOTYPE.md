# ui-prototype/ — UI 原型设计专区

UI 原型（页面 / 屏幕 / 演示数据）的**独立设计区**，与实际前端工程 [`workspace/apps/frontend/`](../apps/frontend/FRONTEND.md) 物理隔离：原型迭代只在本目录进行，不进入前端工程源码。

## 来源

收编自已删除的 `prototype/ui` 分支（tip `82e1493`，24 个原型提交），文件**逐字拷贝、未改内容**。原分支上对前端工程的接线改动未随迁，以 [`docs/frontend-wiring.patch`](docs/frontend-wiring.patch) 存档。

## 目录映射

| 本区 | 原分支路径 | 说明 |
|---|---|---|
| `web/` | `frontend/web/src/prototypes/` | Web 原型：pages/ components/ shell/ + 路由注册 |
| `mobile/` | `frontend/mobile/src/screens/prototypes/` | APP 原型屏（RN 等价形态） |
| `core/src/` | `frontend/packages/core/src/prototype/` | 演示数据层（确定性生成，web/mobile 共用） |
| `core/tests/` | `frontend/packages/core/tests/prototype*.test.ts` | 数据层回归测试 |
| `docs/decisions/` | `.ai-memory/decisions/`（02–07 prototype ADR） | 原型设计决策，编号保留原样 |

## 恢复可运行状态的接线点（详见 docs/frontend-wiring.patch）

- Web：`web/src/App.tsx` 挂载原型路由壳；`web/src/pages/Settings.tsx` 增加入口
- Mobile：`mobile/App.tsx` 导航注册；`mobile/src/components/ui.tsx`、`mobile/src/screens/SettingsScreen.tsx`
- Core：`packages/core/src/index.ts` 导出 `prototype/*`

## 边界与依赖

- 原型页依赖 `@acme/core`（主题令牌 / 组件 / 类型）与 Vite / RN 工具链；本区为设计归档，**不参与** pnpm 工作区构建，恢复运行需按上方接线点重建。
- `core/src/` 数据层引用 core 正式类型（如 `../types`）；原型成熟化时，把对应函数迁入正式业务域，其余随原型删除（沿袭原区约定，见 [docs/decisions/2026-11-12-02-prototype-zone.md](docs/decisions/2026-11-12-02-prototype-zone.md)）。
- 演示数据全部确定性生成（同输入同输出）；不含真实凭证（密钥仅脱敏形态）。
