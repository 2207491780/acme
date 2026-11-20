# 基座项目完成项盘点（2026-11-20）

> 快照口径：分支 `acme-foundation`（214 commits，工作树 clean）＋ `contracts/` 31 份契约现场。
> 契约状态以各文件「流程状态」节为准；本文为盘点草稿，需求推进后随时改写。

## A. 工程底座（git 主线交付，非契约需求）

- **AI-Coding 配置工程**：`.ai/` 单源派生体系（sync.mjs，11 类工具生效）、三钩子（pre-commit/commit-msg/pre-push）+ CI 校验、worktree.mjs 并行开发（含 gc）、契约驱动多代理流程（team-leader 四阶段 + contracts/ 现场协调）。
- **后端工程**：Java25 + Spring Boot 4.1 多模块 Maven，`acme-parent` + `acme-cloud`（common→framework→platform→business→gateway，45 模块；framework 22 个能力模块）；WebSocket（站内信推送）。
- **前端工程**：pnpm monorepo 四端——`packages/core`（框架无关业务+令牌+视觉事实源 theme.ts）、`web`（React18/Vite5/AntD5）、`desktop`（Electron37 + 自动更新）、`mobile`（Expo52/RN0.76）。
- **部署栈**：k3s base+overlays + deploy.sh（互斥锁、`ACME_*_SERVICES` 增量发布）；CI/CD 五镜像推 TCR、SSH 部署、prod 人工审批；两台轻量机分层形态（单套中间件 + 本地 CI/CD + MinIO/Kafka）；`acme-bootstrap` 单体形态双打包。
- **数据迁移**：V2~V23 全部 DONE 占用（V21 个人中心 → V23 重编号修正）。

## B. 已完成契约需求（26 份 DONE，均已合流）

### B1 后端平台能力（9）
| REQ | 交付 |
|---|---|
| 071607 | 文件服务（本地盘）/ Excel 通用能力 / 任务调度 |
| 120102 | 消息通知闭环：站内信+通知公告+WebSocket+前端通知中心 |
| 120104 | 组织架构与数据权限：部门/岗位/角色-数据范围 |
| 120105 | 登录安全域：登录日志+在线用户管理（踢下线） |
| 120106 | 网关增强：聚合 API 文档/XSS 清洗/灰度选择器 |
| 120109 | 敏感词过滤（自研 DFA 词库+管理端+检测 API） |
| 230354 | 租户级系统配置（安全策略强制生效）+管理页 |
| 011514 | 租户级验证码开关（默认关闭） |
| 025200 | 租户机制补强＋租户页脚版权声明 |

### B2 后端延伸（4）
| REQ | 交付 |
|---|---|
| 045754 | 对象存储直传与租户配额（可配置） |
| 061017 | 日志中心完善＋定时任务管理页 |
| 002130 | 单体/分布式双形态打包（acme-bootstrap） |
| 040838 | APP 扫码登录（QR ticket 状态机＋APP 员工登录） |

### B3 前端通用层与权限（5）
| REQ | 交付 |
|---|---|
| 120101 | CRUD 组件推广/字典消费/文件上传下载/Excel 组件/动态菜单 |
| 120103 | 前端基建加固：i18n 全量/ErrorBoundary/请求层取消超时去重/mock/env 分层 |
| 051119 | 按钮级权限（BUTTON 菜单节点＋前端 Auth 原语，V20） |
| 151640 | 六页菜单叶子补齐＋BUTTON 归位（V22） |
| 082531 | 个人中心：资料/头像/自助改密/会话/偏好/APP 推送（V21；V23 重编号修复） |

### B4 多端覆盖（5）
| REQ | 交付 |
|---|---|
| 120110 | mobile 对齐一期：权限门禁/meStore/AsyncStorage/字典消费 |
| 032108 | APP 脚手架完善：导航/双主题/i18n/深链/基建 |
| 120111 | desktop 自动更新＋系统托盘（electron-builder 出包实测过） |
| 120112 | 体验组件：Dashboard 图表/全局水印/锁屏/全屏 |
| 011524 | web 首页——平台概览统计聚合与 widget 改版 |

### B5 web 体验迭代（3）
| REQ | 交付 |
|---|---|
| 063129 | 多页签＋页面缓存＋全局命令面板 |
| 104022 | 页签条滚动增强＋hover 结构化预览卡 |
| 120552 | 首屏加载动画（品牌 splash）＋命令面板搜索历史 |

### B6 无契约的质量/重构波（git 主线）
- Docker 真跑集成测试债清理（883909e）；早期 7 份契约「PG/Redis SKIP≠验证」债由此+151640（Docker 全量三轮 BUILD SUCCESS）清偿。
- 重构三波：框架层收敛 / 服务层与 auth-infra 收敛（N+1 修复）/ system 收敛（内置角色回退共用、父链索引直查）。
- 测试稳定性加固（web testTimeout 20s、mobile Toast 时窗、vitest worker 上限 4）；api 样板收敛与持久化单源；deploy.sh 互斥锁与增量发布；页签紧凑化迭代轮次 1。

## C. 门禁在途（3 份，分支/工作树在场）

| REQ | 阶段 | 现场 |
|---|---|---|
| 213603 菜单管理筛选 | III-GATE round3 | G1/2/3/5 PASS；G4 轮3 Integration 1 例失败归因 oracle 错（98%），tester 修两处 oracle 后待复跑 |
| 214653 权限点管理筛选 | III-GATE round2 | 前端 PASS（474/474）；后端 testCompile 类型口径修正后 mvn test 重跑中 |
| 120107 框架注解接线 | III-GATE round0 **停滞**（2026-11-15 起） | G1/2/3/5 PASS；卡在裁决 A 待用户（system/auth 审计 MQ 出口：补桥接 vs 修订 AC-2 口径）；分支/工作树已不存在，但交付物（幂等/限流/审计注解、framework-cache、env-stack MinIO）已随后续需求大体落线——需收口或作废 |

## D. 停滞/暂缓（2 份）

- **120113 E2E Playwright＋错误采集上报**：II-DEV round0 停滞。tester（E2E 用例）与 frontend（采集器+FeErrorsPage 18 文件）已交付，backend 未开工；V4/V12 迁移号仍预占。
- **120108 SSO/OAuth2 一期**：draft 暂缓（用户 2026-11-15 指示）。恢复前先定提供商（GitHub/企业微信/钉钉/飞书）。

## E. 遗留债（按 `.ai-memory/drafts/review-deferred-items.md` 2026-11-14 口径，需逐条核对时效）

- 部署侧：prod Nacos 鉴权未启用（部署专项）、test overlay ingress-host 覆盖、cd.yml scp 残留/TOFU、ci.yml 缓存与 ai-rules-sync 作业重合。
- 后端小项：验证码 GET→DELETE 非原子（Lua 化）、登录账号枚举口径、CORS 缺省语义、SqlMasking 反引号、BCrypt 常量时间等。
- 前端：ESLint+react-hooks 引入（独立任务）、token 存储升级（产品决策）、若干 P3 机械项。
- 杂项在途：`feat/login-prototype` 原型分支未合并；`chore-ai-multi-person-integration`、`sync-ai-mirror-20261117` 工作树在场。

## 统计

- 契约 31 份：**DONE 26** / 门禁在途 3（其一停滞）/ 停滞 1 / 暂缓 1 → 完成率 84%。
- 主线 214 commits；迁移 V2~V23；测试基线：web 474+、core 267+、mobile 66、后端 platform-system 293+（全绿口径见各契约 G4 台账）。
