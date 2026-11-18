# 前端原型阶段启动：范围全量、代码原型、独立目录

日期：2026-11-12
关联：`2026-11-11-02-frontend-react-stack.md`（React 技术栈底座）

## 背景

前端基建（设计令牌 / UI-SPEC / 三端工程 / 191 项测试）就绪后进入原型阶段。经确认：原型范围按选项菜单**全量实施**（A1-A6 / B1-B3 / C1-C3 / D1-D3 / E1-E3 / F1-F5，共 19 项 + 基建）；载体为**代码原型**（React/RN 直接消费 core 令牌，比设计稿更贴合本仓库 AI-Coding 工作流）；PC 端排除（与 Web 同 UI，自动复用）；落点要求独立目录防止混入正式 UI。

## 决定

- **Web**：`web/src/prototypes/`，`/prototype/*` 懒加载路由 + 索引页 + PageShell「原型预览」徽标；不进正式 NAV，入口为设置页「原型预览（内部）」；`registry.tsx` 为唯一登记点。
- **APP**：`mobile/src/screens/prototypes/`，ProtoNavigator 全屏覆盖层（不进正式 tab），入口同为设置页；`ui.tsx Button` 补 `disabled` 与无障碍状态。
- **共享数据**：`packages/core/src/prototype/data.ts` —— 确定性种子（mulberry32）+ 纯函数，经 `@acme/core` 主入口导出（子路径导出在 Metro/Vite 源码别名下不可靠）；附 11 项单测锁定确定性。
- **纪律**：原型只读正式 store，写操作仅本地 useState 并提示「原型演示」；无障碍要求同 UI-SPEC §7；生命周期「原型 → 评审 → 迁入正式 pages/screens 或删除」写于 `web/src/prototypes/README.md`。

## 被否的选项

- **Figma 静态稿优先**：令牌即原型、校验页即验收，代码原型可直接进入成熟化流程；设计稿仅在需要对外评审视觉方向时补充。
- **`@acme/core/prototype` 子路径导出**：Vite/Metro 的 `@acme/core → src/index.ts` 源码别名不解析子路径，会破坏三端复用；改为主入口整包导出。
- **原型页复用正式导航入口**：会造成功用性混淆（用户误以为正式功能）；改为 URL + 设置页显式入口双通道。

## 验证

Web 20 路由浏览器逐页走查（渲染/徽标/无 vite 报错覆盖层）+ 索引/报表/主题矩阵截图核对 + states 交互冒烟；三端 typecheck、191 项单测、web build 全绿。发现并修复「ProtoRoute 与页面双重外壳」缺陷（0a73daf）。
