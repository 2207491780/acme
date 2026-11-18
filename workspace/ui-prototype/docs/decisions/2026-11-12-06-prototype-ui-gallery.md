# 原型增量五：通用 UI 补齐（F7）——管理端通用件闭环

日期：2026-11-12
关联：`2026-11-12-02-prototype-zone.md`（落点）、`2026-11-12-04`（用户域）、`2026-11-12-05`（运营域）

## 背景

对 39 项原型做「管理端通用 UI 是否齐全」审计（grep 组件使用计数），结论：业务域覆盖约 95%，通用组件清单覆盖约 80%，存在 8 个高频缺口：用户菜单（Dropdown）、页内 Tabs、日期/时间三件套、Popconfirm、面包屑、Tree、Popover、Cascader/穿梭分配。

## 决定

1. **Web F7 组件样例库**（`/prototype/components`）：8 缺口全部给出可运行用例 + 使用规格（何时用/何时不用），样式走既有令牌；即成熟化迁移蓝本。
2. **实际接线三处**（组件不止出现在样例页，而是落到真实场景）：
   - 正式顶栏接入用户菜单（Dropdown + Avatar：我的工作台 / 个人详情 / 演示退出）；
   - U2 用户详情审批区改为 Tabs（待办 | 签署记录）；
   - A4 发布日历接入 DatePicker 按日过滤（含清空）。
3. **APP 等价形态**：RN 端以模态用户菜单、Chip 页签、确认弹窗、树形勾选、穿梭 chip、级联 chip 实现（日期滚轮需登记 `@react-native-community/datetimepicker`，列为成熟化方向）。
4. **样例数据**：`core/prototype/ui.ts`（组织树对齐 MEMBERS / 穿梭池 / 级联选项由 store 推导）+ 4 项单测。

## 关键口径

- 可逆操作用「通知+撤销」（正式已实现），不可逆/高风险用 Popconfirm 注明后果——两套确认机制的分界写入样例规格。
- 登录态相关菜单项（退出登录）保留为演示并标注「无登录态（ADR 04）」，不引入假认证。

## 被否的选项

- **antd Transfer 全量组件**：池子仅 6 项，按钮组/chip 已足够且更贴令牌；池子规模扩大时再换。
- **Mobile 日期滚轮自制**：无依赖可用的滚轮实现会引入手势复杂度；登记 datetimepicker 是正路。

## 验证

三端 typecheck、216 项单测、web build 全绿；F7 路由走查 + 顶栏菜单走查 + 截图；组件覆盖 grep 复核（Tabs/DatePicker/Dropdown/Breadcrumb/Tree/Popconfirm/Popover/Cascader/Transfer 由 0 → 非 0）。
