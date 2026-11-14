# 前后端通用能力缺口盘点（2026-11-14）

> 基于源码逐项核实（非仅文档）的快照，供后续需求规划参考；落地一项可勾掉一项。
>
> **2026-11-14 已全部立项为 REQ 契约草稿**（contracts/ 下 status: draft，逐项确认后开工），映射见文末「立项映射」。

## 一、前后端联动缺口（一端就绪、另一端未接，性价比最高）

| # | 能力 | 后端现状 | 前端现状 |
|---|---|---|---|
| 1 | 文件上传/下载 | `platform-infra/FileUseCase` + `framework-storage`（local/minio/oss 三实现，当前 local）已就绪 | 无通用 Upload/下载组件，未接 API |
| 2 | 字典消费 | `SysDictType/SysDictItem` 管理端完整 | 无 `useDict` 缓存 hook、无字典选择器，业务表单未消费 |
| 3 | 动态菜单 | `SysMenu` + `me.menus` 已随 /me 下发 | 侧边导航仍是静态 `NAV` 按权限码过滤，未渲染后端菜单树 |
| 4 | CRUD 组件推广 | — | `use-crud` + `CrudTable/CrudFormModal` 范本仅 UsersPage 采用，其余 6~7 个管理页未收敛 |
| 5 | Excel 导入导出 | `framework-excel` 通用模块就绪（仅 ExcelDemoUseCase 范式用例） | 无导入导出组件（仅 Settings 页私有 JSON 导入导出） |
| 6 | 消息通知闭环 | 无站内信/邮件/WebSocket 推送（SMS 仅验证码通道） | 无通知中心、无 WS/SSE 实时能力——需两端新建 |

## 二、后端缺口

- **组织架构不完整**：无部门/岗位实体；数据权限仅单 scope 单评估器（audit 一处落地），无"角色-数据范围（本部门/自定义部门）"模型。
- **平台功能缺件**：系统参数配置（sys_config）、通知公告、独立登录日志/在线用户管理（踢下线，现靠 AuthSession 间接覆盖）。
- **框架注解接线率低**：`@Idempotent`、`@RateLimit`、`@AuditLog` 业务端点几乎零使用——能力"可用但少用"。
- **SSO/OAuth2 第三方登录**：无。
- **网关**：无文档聚合 UI（仅服务侧 springdoc）、无 XSS 清洗、灰度仅配置壳（无活动路由/用户选择器）。
- **对象存储**：minio/oss 实现已写但环境固定 `type: local`，切换路径未在真实环境验证。
- **杂项**：代码生成器、敏感词、水印、Spring Cache 注解未接（自研 TwoLevelCache API）、`framework-tx` 无实际分布式事务接线。

## 三、前端缺口

- **文案 i18n 框架**：无 i18next/react-intl，全站中文硬编码（日期国际化已做）。
- **请求层加固**：无 AbortController 取消/统一超时/请求去重（竞态防护只在响应侧）。
- **运行时健壮性**：无 ErrorBoundary；无错误上报/埋点。
- **工程配套**：无 mock（MSW/fixture）；无 `.env.development/test/production` 分层文件（靠部署注入）。
- **移动端**：未接权限门禁/meStore、CRUD 组件、字典/审计域；`mobile/src/utils/storage.ts` 为内存态（TODO）。
- **桌面端**：无自动更新、托盘等增值能力（IPC 仅 get-info/open-external）。
- **其他**：无 E2E（Playwright/Cypress）与覆盖率配置、图表库、富文本、水印/锁屏/全屏。

## 四、建议优先级

1. **联动缺口 1/2/4**（上传组件、字典 hook、CRUD 推广）：后端零改动或小改动，前端收敛既有管理页。
2. **消息通知闭环**（站内信 + WS + 前端通知中心）：当前最大整域空白，典型中后台必备。
3. **文案 i18n + ErrorBoundary + 请求取消**：前端基建补强，改造成本随页面增多线性上涨，宜早。
4. **组织架构（部门/岗位）+ 角色-数据范围**：后端 RBAC 收尾，解锁按组织授权的真实业务场景。

## 五、立项映射（2026-11-14，全部 status: draft 待确认）

| REQ 契约 | 覆盖缺口 | 关键预分配 |
|---|---|---|
| REQ-20261114-120101 | 联动缺口①②④⑤⑥的纯前端面：CRUD 推广、字典消费 hook/选择器、上传下载组件+文件页、Excel 导入导出组件、动态菜单消费 | 无（纯前端） |
| REQ-20261114-120102 | 消息通知闭环：站内信+通知公告（含富文本）+WebSocket 推送+前端通知中心 | platform-system V8；新依赖 spring-boot-starter-websocket、tiptap |
| REQ-20261114-120103 | 前端基建：i18next 全量迁移、ErrorBoundary、请求取消/超时/去重、MSW mock、.env 分层 | 无；新依赖 i18next/react-i18next/msw |
| REQ-20261114-120104 | 组织架构：部门/岗位 + 角色-数据范围五档模型 + evaluator 扩展 | platform-system V9 |
| REQ-20261114-120105 | 登录安全：登录日志 + 在线用户/踢下线（复用撤销集） | platform-auth V6 + platform-system V10 |
| REQ-20261114-120106 | 网关：聚合文档 UI、XSS 清洗、灰度用户选择器 | 无库表 |
| REQ-20261114-120107 | 框架接线：@Idempotent/@RateLimit/@AuditLog 推广、Spring Cache 适配、minio 切换验证 | 无库表；dev overlay 加 minio |
| REQ-20261114-120108 | SSO/OAuth2 一期（B 端，提供商待指定） | platform-auth V7 |
| REQ-20261114-120109 | 内容安全：敏感词（自研 DFA + 词库管理 + 检测 API） | platform-infra V3 + platform-system V11 |
| REQ-20261114-120110 | mobile 对齐：权限门禁、meStore、AsyncStorage、字典消费 | 无；新依赖 async-storage |
| REQ-20261114-120111 | desktop 增值：自动更新 + 托盘 | 无；新依赖 electron-updater |
| REQ-20261114-120112 | 前端体验：Dashboard 图表、水印（antd 内置）、锁屏、全屏 | 无；新依赖 echarts |
| REQ-20261114-120113 | 质量与观测性：Playwright E2E + 错误采集上报 | platform-infra V4 + platform-system V12；新依赖 @playwright/test |

- 迁移号=TL 预分配（基线：auth V5 / system V7 / infra V2），并行合流顺序不同时开工需 TL 复核重排。
- 通知推送路线结论：WebSocket（性能对比记录在 120102 确认记录①）；i18n=i18next 全量迁移（用户裁决）。
