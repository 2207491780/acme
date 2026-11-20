# ACME 基座功能差距分析与完善规划

> 快照日期：2026-11-20 ｜ 对照基线：`acme-foundation`（214 commits，契约 26/31 DONE）
> 方法：横向调研主流开源中后台基座的功能点全集，逐域对照 ACME 现状（以代码/页面实测为准），给出分级完善建议。
> 本文档是规划快照，需求立项后以 `contracts/` 契约为准。

## 1. 结论摘要

ACME 在**身份权限、组织租户、日志审计、消息通知、文件/Excel/任务、多端交付（web/desktop/mobile）、部署与 CI/CD** 上已达到或超过主流基座水平；主要缺口集中在四类：

| 缺口类别 | 代表功能 | 紧迫度 |
|---|---|---|
| **开发效率** | 代码生成器（所有主流基座标配，ACME 缺失的最大单项） | P0 |
| **监控运维** | 服务/缓存/连接池监控页、链路追踪 APM、熔断降级 | P0~P1 |
| **企业集成** | 短信/邮件通知通道、SSO/三方登录（已暂缓）、OpenAPI 开放平台、文件在线预览、MFA | P0~P1 |
| **高阶能力** | 工作流 BPM、报表/大屏设计器、AI 模块、低代码表单 | P1~P2 |

## 2. 调研范围

| 项目 | 技术栈 | 定位 | 对 ACME 的参考价值 |
|---|---|---|---|
| RuoYi（Vue/Cloud） | Spring Boot / Spring Cloud Alibaba | 最广泛使用的最小功能集 | 基座下限（标配清单） |
| RuoYi-Vue-Plus（dromara） | Spring Boot + Sa-Token + MyBatis-Plus | 企业级增强版（无业务插件） | **最接近 ACME 定位的对照** |
| 芋道 yudao（ruoyi-vue-pro） | Spring Boot 多模块 | 模块化全家桶（BPM/支付/AI/商城） | 增值功能上限参考 |
| JeecgBoot | Spring Boot + Vue3 | 低代码 + 报表大屏 + AI | 低代码/报表/开放平台参考 |
| Snowy / Snowy-Cloud | Spring Boot + Sa-Token | 国密 + 国产化 + 插件化 | 安全增强/国产化参考 |
| pig / pig4cloud | Spring Cloud + OAuth2（SAS） | 生产级认证中心 + 微服务治理 | OAuth2 Server/网关治理参考 |
| Gin-Vue-Admin | Go + Vue | Go 系对照 | Casbin API 级鉴权、插件市场 |
| vue-pure-admin | Vue3 纯前端基座 | 前端工程化对照 | 主题/布局/构建优化 |

独立能力 donor（基座常集成）：XXL-Job（分布式调度）、Flowable/Warm-Flow（工作流）、JimuReport/积木 + DataEase + GoView（报表/大屏）、kkFileView（文件预览）、MaxKey/CAS（SSO Server）、Sentinel（流量治理）、SkyWalking（APM）。

## 3. 开源基座功能点全集（分域）

### A. 身份与权限
用户/角色/菜单/部门/岗位管理、按钮级权限、API 级权限（Casbin）、数据权限（部门/自定义 SQL 过滤）、字典、在线用户（踢下线）、多客户端管理（token 时效）、MFA/二次认证、密码策略。

### B. 组织与租户
多租户 SaaS（租户套餐）、租户级配置、数据脱敏（注解 + Jackson，手机/身份证/卡号）、数据加解密（AES/RSA/SM2/SM4）、接口传输加密、国密整链（Snowy：登录 SM2/密码 SM3/字段 SM4/日志签名防篡改）。

### C. 系统管理
参数配置（sys_config）、通知公告、地区管理、错误码管理、i18n 多语言、数据库文档（Screw）。

### D. 日志审计与安全
操作日志（含异常）、登录日志、API 访问/异常日志、数据快照对比、字段级数据变更审计、敏感词、XSS 清洗、防重放、验证码。

### E. 消息与通知
站内信、WebSocket 推送、短信（多厂商 sms4j/阿里/腾讯）、邮件、多渠道聚合推送（钉钉/企微/飞书/微信公众号模板消息）、消息模板管理。

### F. 文件与内容
文件服务（本地/S3/MinIO/OSS/FTP/DB）、直传+配额、文件在线预览（kkFileView，Office/PDF/图片/视频）、素材库/网盘、CMS。

### G. 任务与调度
定时任务管理（Quartz/自研）、分布式调度（XXL-Job/SnailJob：分片广播、失败重试、DAG）、执行器管理、调度日志。

### H. 监控运维
服务监控（CPU/内存/磁盘/JVM 堆栈）、缓存监控（Redis 命中/键）、连接池/SQL 监控（Druid 分析、慢 SQL）、Spring Boot Admin（在线日志）、链路追踪（SkyWalking）、性能扫描（Redis/Tomcat/JVM/磁盘/请求追踪）、告警。

### I. 开发效率
**代码生成器**（单表/一对多，前后端全量生成，主流基座 100% 标配）、表单构建器（拖拽）、API 文档聚合（Swagger/SpringDoc）、数据库文档、导出中心（大数据量异步导出）。

### J. 流程与低代码
工作流 BPM（Flowable 双设计器/Warm-Flow：会签/或签/加减签/转办/委派/超时）、表单设计器、Online 零代码表单/报表（JeecgBoot）、报表设计器（积木）、大屏设计器（JimuBI/GoView/DataEase）。

### K. 集成与开放
三方登录（JustAuth：微信/钉钉等）、SSO/OAuth2 Client、**OAuth2 Server/SSO 服务端**（pig SAS/CAS）、支付（支付宝/微信/模拟）、OpenAPI 开放平台（AK/SK 鉴权、接口授权、签名、webhook）、微信/钉钉/企微通讯录同步、AI 模块（对话/知识库 RAG/绘画/MCP 工具/Chat2BI）。

### L. 微服务治理
网关（路由/鉴权/灰度）、Sentinel 熔断限流、分布式事务（Seata）、多数据源/读写分离（dynamic-datasource）、分布式锁/幂等/限流组件、MQ、雪花 ID、全链路灰度。

### M. 前端基座体验
RBAC 动态路由、多页签、i18n、暗黑主题 + 多种布局、命令面板、水印/锁屏/全屏、组件文档站、移动端（uniapp/RN）、桌面端（Electron）、E2E 测试、错误采集上报、构建优化（CDN/brotli 精简版）。

## 4. ACME 现状对照

图例：✅ 已有 ｜ 🟡 部分/在途 ｜ ❌ 缺失

### A. 身份与权限 — 基本齐平
| 功能 | 状态 | 说明 |
|---|---|---|
| 用户/角色/菜单/部门/岗位 | ✅ | platform-system 六页齐（V22 菜单叶子补齐） |
| 按钮级权限 | ✅ | BUTTON 节点 + 前端 Auth 原语（V20） |
| 数据权限 | ✅ | 角色-数据范围（120104） |
| 字典/在线用户 | ✅ | DictsPage、OnlineUsersPage |
| MFA/二次认证 | ❌ | 仅密码策略（租户级配置）+ 验证码 |
| API 级鉴权（Casbin） | ❌ | 以 RBAC+权限点实现，无独立 API 鉴权引擎（可接受） |

### B. 组织与租户 — 强项
| 功能 | 状态 | 说明 |
|---|---|---|
| 多租户 + 租户级配置 | ✅ | TenantsPage、SystemConfigPage（安全策略强制生效）、验证码开关、页脚 |
| 数据脱敏（API 响应） | ❌ | 仅 SqlMasking（SQL 日志脱敏），无响应脱敏注解 |
| 数据加解密/传输加密/国密 | ❌ | 无 |

### C. 系统管理 — 齐平
参数/租户级配置 ✅、公告 ✅、i18n（前端全量 + 后端错误码 framework-i18n）✅；地区管理 ❌（低频）、错误码管理页 ❌（代码内维护）、数据库文档 ❌。

### D. 日志审计与安全 — 强项
操作/审计日志 ✅（AuditPage + framework-audit）、登录日志 ✅、异常/日志中心 ✅（ExceptionsPage）、敏感词 ✅（DFA+管理端）、XSS 网关清洗 ✅、验证码 ✅；字段级数据变更审计 ❌、数据快照对比 ❌。

### E. 消息与通知 — 半齐平
站内信 + WebSocket + 通知中心 ✅（120102）；**短信通道 ❌**（仅登录验证码内部件，无通知级多厂商短信）、**邮件 ❌**、多渠道聚合（钉钉/企微/飞书）❌、消息模板管理 ❌。

### F. 文件与内容 — 基本齐平
文件服务（本地盘+OSS 直传+租户配额）✅、Excel 导入导出 ✅；**文件在线预览 ❌**；素材库/网盘 ❌；CMS ❌（business-content 已有意下线，公告覆盖基本需求）。

### G. 任务与调度 — 齐平
定时任务 + 管理页 ✅（JobsPage + framework-job）；分片广播/DAG/失败重试告警 🟡（自研调度无分布式增强）。

### H. 监控运维 — **主要缺口**
| 功能 | 状态 | 说明 |
|---|---|---|
| 服务监控页（JVM/磁盘/堆栈） | ❌ | framework-observability 模块在，但无运维可视化页面 |
| 缓存监控（Redis） | ❌ | |
| 连接池/SQL 全链路监控 | ❌ | 慢 SQL 参数日志仅有脱敏 |
| 链路追踪 APM | 🟡 | framework-trace 在（链路 ID），未接 SkyWalking 类系统 |
| 熔断降级 | ❌ | 网关无 Sentinel/resilience4j |
| 告警 | ❌ | |

### I. 开发效率 — **最大缺口**
| 功能 | 状态 | 说明 |
|---|---|---|
| **代码生成器** | ❌ | 全部主流基座标配；ACME 新模块（如 213603/214653）仍纯手写 CRUD+测试 |
| 表单构建器 | ❌ | |
| API 文档聚合 | ✅ | 网关 springdoc 聚合（120106） |
| 异步导出中心 | ❌ | ExcelHttpExporter 为同步 HTTP 导出 |
| E2E 测试 + 错误采集 | 🟡 | 120113 停滞：tester/frontend 已交付，backend 未开工 |

### J. 流程与低代码 — 缺失（属增值层）
工作流 BPM ❌、表单设计器 ❌、报表设计器 ❌、大屏设计器 ❌（Dashboard 有 echarts widget，但非设计器）。

### K. 集成与开放 — 缺失为主
| 功能 | 状态 | 说明 |
|---|---|---|
| SSO/三方登录 | 🟡 | 120108 契约暂缓（draft），恢复先定提供商 |
| OAuth2 Server（对外 IdP） | ❌ | 仅自有登录 |
| OpenAPI 开放平台（AK/SK+签名+webhook） | ❌ | 网关仅文档聚合 |
| 支付/微信公众号 | ❌ | 业务域，视产品定位 |
| AI 模块（对话/RAG/MCP） | ❌ | 2026 年新基座趋势（yudao/Jeecg 均已内置） |

### L. 微服务治理 — 基本齐平
网关（文档/XSS/灰度选择器）✅、幂等/限流注解 ✅（120107 已落线）、分布式锁 ✅（framework-lock）、MQ ✅（framework-stream + Kafka）、单体/分布式双形态 ✅（acme-bootstrap）；熔断 ❌、多数据源/读写分离 ❌、分布式事务（Seata）❌、全链路灰度 🟡（有用户选择器，无全链路透传）。

### M. 前端基座体验 — 强项
多页签+缓存+命令面板 ✅、i18n ✅、亮/暗双主题 ✅（core/theme.ts）、水印/锁屏/全屏 ✅、首屏 splash ✅、组件文档站 ✅（ui:guide 生成）、desktop（Electron37+自动更新+托盘+出包）✅、mobile（Expo52，扫码登录/双主题/i18n/深链）✅；布局形态切换 🟡（单一布局）、构建体积优化未专项做。

## 5. 完善建议（分级）

规模：S≈1~3 天 ｜ M≈1~2 周 ｜ L≈2~6 周（含测试与门禁，按契约流程走）

### P0 —— 基座标配补齐（建议下一迭代周期）

| # | 功能 | 现状 | 建议方案 | 规模 |
|---|---|---|---|---|
| 1 | **代码生成器** | 全手写 | 后端先行：读库表元数据→模板生成 Controller/UseCase/Repository/Flyway/单测（对齐 mybatis-flex + 现有分层惯例）；二期生成 core api + web 页面 + msw handler。放 platform-infra `dev` 域 | L |
| 2 | **服务与缓存监控页** | 模块在无页面 | Actuator 暴露指标（JVM/磁盘/Hikari）+ Redis info；web 新增 MonitorPage（复用 Dashboard echarts）；网关放行运维权限点 | M |
| 3 | **API 响应脱敏注解** | 仅 SQL 脱敏 | Jackson `@Sensitive` + 序列化器（手机/身份证/邮箱等策略），对齐现有权限体系 | S |
| 4 | **异步导出中心** | 同步导出 | 复用 framework-job + 文件服务：导出任务表→异步生成→文件服务落盘→通知中心推送下载链接 | M |
| 5 | **短信/邮件通知通道** | 仅登录验证码 | infra 域短信（多厂商 SPI，先阿里云/腾讯云二选一）+ 邮件（spring-boot-starter-mail）；通知中心多渠道分发 + 模板管理页 | M |

### P1 —— 企业集成与运行时韧性

| # | 功能 | 建议方案 | 规模 |
|---|---|---|---|
| 6 | **工作流 BPM** | 轻量起步选 Warm-Flow（国产、无重型依赖、会签/或签/转办/委派齐），流程设计器用其前端方案或自研简化版；Flowable 仅在需 BPMN 全量时选 | L |
| 7 | **SSO/三方登录** | 恢复 120108 契约（draft 已有范围）：先 OAuth2 Client（GitHub/企微/钉钉/飞书定一家）；如需对外 IdP 再立项 OAuth2 Server（Spring Authorization Server，参考 pig） | M |
| 8 | **OpenAPI 开放平台** | AK/SK 签名鉴权 + 接口授权管理页 + 回调 webhook（复用 framework-stream）；网关层验签过滤器 | M~L |
| 9 | **文件在线预览** | 短期：图片/PDF/文本直出；Office 类接 kkFileView 容器（env-stack 加编排），文件服务 url 透传 | S~M |
| 10 | **MFA/二次认证** | TOTP（复用扫码登录 QR 状态机经验）；敏感操作（改密/踢人/配置变更）二次确认 | M |
| 11 | **消息多渠道推送** | 通知中心扩展渠道适配器（钉钉/企微/飞书群机器人 webhook 起步），模板变量渲染 | S~M |
| 12 | **熔断降级** | gateway + feign 接 resilience4j（轻）或 Sentinel（带控制台）；降级兜底页 | S~M |
| 13 | **E2E + 错误采集收口** | 重启 120113（backend V4/V12 补齐），错误上报接通后评估接 Sentry/自建聚合 | M |

### P2 —— 高阶能力与前瞻（按产品节奏立项）

| # | 功能 | 说明 | 规模 |
|---|---|---|---|
| 14 | 报表/大屏设计器 | 先做「配置化仪表盘」（Dashboard widget 用户可配置），设计器级引 GoView/积木思路再评估 | L |
| 15 | AI 模块 | 对话 + 知识库（RAG）+ MCP 工具接入；先做对话（接 GLM/通义等 OpenAI 兼容接口），知识库二期 | L |
| 16 | 低代码表单设计器 | 与工作流表单权限联动设计；避免 JeecgBoot Online 全家桶体量 | L |
| 17 | 多数据源/读写分离 | dynamic-datasource 思路；当前单库+PG 规模下非急需 | M |
| 18 | 字段级数据变更审计 | MyBatis 拦截器 diff 落库 + 审计页展示 | M |
| 19 | 全链路灰度 | 灰度选择器扩展为标签透传（网关→feign→MQ） | M |
| 20 | 国产化适配 | 达梦/金仓驱动适配 + 国密（SM2/SM4）整链（参考 Snowy） | L |
| 21 | 质量工程 | SonarQube 接入 CI、测试覆盖率门禁、压测基线（k6/JMeter） | M |
| 22 | 分布式调度增强 | framework-job 补分片广播/失败重试/告警（或评估替换 XXL-Job） | M |

### 明确不建议引入（定位决策，持续有效）

- **商城/CRM/ERP/会员/分销**：业务域，超出基座定位（yudao 有但 ACME 非全家桶路线）。
- **CMS**：business-content 已下线（d5b77b3），公告已覆盖。
- **微信公众号运营**：B 端基座不涉。
- **低代码平台化**（Online 全家桶）：体量过重，保持 P2 评估口。

## 6. 与既有契约/遗留项的衔接

| 既有项 | 衔接 |
|---|---|
| 120108 SSO/OAuth2（暂缓） | → 建议 P1-7 恢复；提供商未定时可先做 OAuth2 Client 框架 |
| 120113 E2E+错误采集（停滞） | → P1-13；V4/V12 迁移号已预占，重启成本低 |
| 120107 框架注解接线（停滞） | → 先收口（裁决 A）再排新需求，避免审计域二次返工 |
| review-deferred-items 债 | P0-2 监控页可顺带处理「网关 CORS 缺省语义」；ESLint 引入建议随 P0-1 代码生成（生成代码需 lint 基线） |
| 多人协作纪律 | 大项（P0-1/P1-6）按 team-leader 契约流程走；并行契约需预占迁移号与 API 命名空间 |

## 7. 附录：调研来源

- RuoYi：[RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue) ｜ [RuoYi-Cloud](https://gitee.com/y_project/RuoYi-Cloud) ｜ [doc.ruoyi.vip](http://doc.ruoyi.vip)
- RuoYi-Vue-Plus：[gitee](https://gitee.com/dromara/RuoYi-Vue-Plus) ｜ [plus-doc.dromara.org](https://plus-doc.dromara.org)
- yudao：[github](https://github.com/YunaiV/ruoyi-vue-pro) ｜ [doc.iocoder.cn](https://doc.iocoder.cn) ｜ [cloud.iocoder.cn/feature](https://cloud.iocoder.cn/feature)
- JeecgBoot：[github](https://github.com/jeecgboot/JeecgBoot) ｜ [help.jeecg.com](https://help.jeecg.com) ｜ [jeecg.com](https://www.jeecg.com)
- Snowy：[github](https://github.com/xiaonuobase/Snowy) ｜ [Snowy-Cloud](https://github.com/xiaonuobase/Snowy-Cloud)
- pig：[github](https://github.com/pig-mesh/pig) ｜ [pig4cloud.com](https://www.pig4cloud.com/)
- Gin-Vue-Admin：[github](https://github.com/flipped-aurora/gin-vue-admin) ｜ [gin-vue-admin.com](https://www.gin-vue-admin.com)
- vue-pure-admin：[github](https://github.com/pure-admin/vue-pure-admin) ｜ [pure-admin.cn](https://pure-admin.cn/)
- Donor：XXL-Job、Flowable、JimuReport、DataEase、GoView、kkFileView、MaxKey/CAS、Sentinel、SkyWalking（各官方仓库/文档）
