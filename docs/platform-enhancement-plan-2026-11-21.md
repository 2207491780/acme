# ACME 平台能力增强规划：BPM 工作流 / 调度中心 / 租户强化 / 主题定制

> 快照日期：2026-11-21 ｜ 上游依据：`docs/feature-gap-analysis-2026-11-20.md`（§5 P1-6 工作流、P2-22 调度增强、§4B/§5 租户、§4M/§7 主题）
> 选型裁决（用户拍板 2026-11-21）：**BPM 引擎与调度中心均纯自研**（参考主流开源优缺点，核心指标=灵活+性能），设计器用符合 React 技术栈的主流图编辑引擎自研——详见 ADR `2026-11-21-02-bpm-job-self-build.md`。
> 定位：立项前规划快照。**每个任务开工一律走 team-leader 契约流程（`/req` → `contracts/REQ-*.md`）**，本文档不替代契约；任务 ID 即后续 REQ 的规划锚点。

## 1. 结论摘要

| 工作流 | 交付形态 | 任务数 | 总规模 |
|---|---|---|---|
| WS-BPM：BPMN 2.0 工作流服务 | 新独立后端服务 `platform-workflow` + 设计器 + 待办中心 + 集成 SDK | 7 | L×6 |
| WS-JOB：独立定时任务调度中心 | 新独立后端服务 `platform-scheduler` + 执行器 SDK + 管理页 | 6 | L×2 |
| WS-TENANT：租户强化 | `platform-system` 租户域增强（套餐/功能分配/子租户/配额） | 5 | M~L |
| WS-THEME：主题/外观深度定制 | 前端三端（core 令牌引擎 + web 偏好抽屉 + 后端偏好持久化） | 4 | M×3~L |

**推荐开发顺序**（7 轮，详见 §8）：主题快赢先行 → 租户强化（套餐+能力开关）为两个新服务打地基 → 调度中心先于 BPM（BPM 定时边界事件复用调度中心）→ BPM 体量最大、预研（BPM-0）即刻启动只读并行。两个新服务的部署接入清单已有现成惯例（§3.1），无探索成本。

规模口径沿用差距分析：S≈1~3 天 ｜ M≈1~2 周 ｜ L≈2~6 周（含测试与门禁）。

## 2. 选型裁决与依据

### 2.1 BPM 引擎：纯自研（ADR 2026-11-21-02）

| 候选 | BPMN 2.0 完整性 | 性能 | 灵活度 | 与 ACME 贴合 | 主要否因 |
|---|---|---|---|---|---|
| Flowable 7 | 参考级 | 中（历史级别/多库兼容层拖累） | 需深入内核定制 | SpringBoot4.1/Java25 兼容存疑 | 模型重、定制成本高 |
| Camunda 8 | 完整 | 强（Zeebe） | 中 | 需 ES/Kafka 重基建 | 运维成本、Client 模型差异 |
| Warm-Flow | 类 BPMN | 轻量好 | 中 | 好 | 非 BPMN 2.0 完整规范 |
| **自研** | 交换层坚持标准 XML，语义分期 | **高**（事件驱动无轮询、≤8 表精简模型） | **最高**（行为 SPI/委托协议全开放） | **最高** | 语义正确性靠对照测试集+长期维护承诺 |

自研核心主张：参考 Flowable 的命令式内核与 token 语义、Warm-Flow 的审批操作语义、Camunda 8 的事件驱动思想，但持久化模型、并发控制、扩展点全部按 ACME 现有惯例（mybatis-flex、@TenantId、framework-stream）重新设计。

### 2.2 调度中心：纯自研（ADR 2026-11-21-02）

| 候选 | 精度 | 分片/重试/DAG | 贴合度 | 主要否因 |
|---|---|---|---|---|
| XXL-Job | 秒级（DB 轮询） | 齐全 | 管理台独立栈，与现有资产零复用 | 触发走 DB 轮询、模型固化 |
| SnailJob | 好 | 强（重试域/DAG） | 社区新 | 引入整套执行协议与 UI 栈 |
| PowerJob | 好 | 强 | 中 | 收益不明显 |
| **自研** | 秒级（时间轮+内存队列） | 分期建 | **最高**（ShedLock/framework-job/Kafka/通知中心全复用） | — |

### 2.3 设计器引擎：React 生态图编辑库自研

推荐 **AntV X6**（React18/AntD5 生态贴合、React 节点渲染、流程图编辑器主流选择；配套 `@antv/x6-react-shape` 用 React 组件画节点，属性面板直接复用现有 AntD 表单惯例）。备选 React Flow（更轻、声明式，但复杂 BPMN 交互如连线路由/对齐线需更多自研）。终稿由 BPM-0 预研 ADR 定案。新增依赖须按规则登记 `.ai/rules/project.md`。

## 3. 现状事实底座（调研结论，2026-11-21 实测）

### 3.1 后端

- **服务拓扑**：platform-infra(9880)/platform-system(9881)/platform-auth(9882)/gateway(9888)，Nacos 注册、网关 `lb://platform-{name}`；另有单体形态 acme-bootstrap（聚合三服务，本地短路适配器）不部署交付。技术栈：Java 25 + Spring Boot 4.1.1 + mybatis-flex 1.11.8 + PG 16 + ShedLock 5.16。
- **新服务接入清单**（惯例现成）：网关 `acme-gateway/application.yml` 路由段+springdoc 分组 → `env-stack/scripts/seed-nacos.sh` 种子 → `env-stack/k8s/base/<svc>.yaml`+kustomization → `deploy.sh` `ALL_SERVICES` 与镜像映射；ingress 只指 gateway 不用动。
- **租户现状**：`sys_tenant` 仅基础列（V17 补 contact/expires_at，过期拒绝登录）；隔离靠 mybatis-flex `@TenantId` + `TenantContext` + fail-close 哨兵（framework-tenant）；租户级配置= `sys_config` 每租户一行；**无套餐/功能分配概念、无子租户**；配额仅文件配额一处硬编码（platform-infra `FileUseCase`）。
- **framework-job 现状**：`@Scheduled` + `@AcmeTask`（taskId/mutex/ShedLock 互斥），内存 TaskRegistry；**无动态 cron 管理、无分片、无重试告警**；后端 API `/admin/v1/infra/jobs`（列表/手动触发/历史），执行历史 `sys_job_execution`。
- **迁移号水位**：platform-system 已到 V23；新服务迁移空间各内自 V1 起。

### 3.2 前端

- **主题体系**：`packages/core/src/theme.ts` 为视觉唯一事实源（颜色/尺寸/字体/动效/密度令牌 + 亮暗两份 NeutralPalette），web `buildTheme()` 映射 AntD ThemeConfig；现有外观选项：亮暗切换、密度 2 档、6 预设强调色、动效开关；水印/锁屏为租户级配置（systemConfigStore）。
- **关键可复用资产**：个人偏好后端 API **已存在**（`/admin/v1/system/me/preferences`，sys_user_preference.prefs jsonb 单行 upsert，当前仅 messagePush）；core 小 store 版本化持久化惯例（`store/persist.ts`）；多页签（tabsStore）已实现。
- **vben v5 对照缺口**（详见 §7.1 对照表）：跟随系统、自定义取色器、圆角/灰度/色弱、布局模式、页签样式、半深色侧栏/顶栏、切换动画选项、偏好抽屉（现为整页设置）、偏好后端持久化。

## 4. WS-BPM：BPMN 2.0 工作流服务（platform-workflow）

### 4.1 架构总览

- **独立服务**：建议名 `platform-workflow`（*-api/*-service 双模块，对齐现有惯例），网关 `/admin/v1/workflow/**`（命名契约阶段定稿），Nacos 注册 + 网关路由；单体形态 bootstrap 可选聚合（契约确认项）。
- **租户**：流程实例/任务走 `@TenantId` 隔离；流程定义平台级共享 + 租户级启用（经 WS-TENANT 的「服务能力开关」控制租户是否开通流程能力）。
- **集成模型**（其他服务都在该服务配置流程的落点）：
  1. **集成 SDK**（新 framework 层模块 `framework-workflow` 或 client 包）：启动流程/推进任务/流程变量读写，Feign + 本地短路适配器（bootstrap 形态对齐现有 LocalTenantClient 惯例）。
  2. **ServiceTask 委托协议**：业务服务暴露内部委托端点（或订阅 Kafka 事件，framework-stream），引擎按节点配置回调。
  3. **流程事件流**：实例启动/节点到达/实例结束 → Kafka 事件（framework-stream），供通知中心/审计消费。
  4. **定时事件复用调度中心**：定时边界事件/超时催办注册到 platform-scheduler（WS-JOB），引擎作为其执行器接入。
- **待办中心**：web 端「我的待办/已办/我发起 + 审批操作页」，desktop 套壳自动继承；mobile 二期。

### 4.2 BPMN 2.0 语义分期（交换层始终为标准 BPMN 2.0 XML）

| 期 | 元素/语义 |
|---|---|
| 一期 | Process/StartEvent(none)/EndEvent/SequenceFlow(条件表达式)/ExclusiveGateway/ParallelGateway/UserTask(指派：用户/角色/部门/发起人；通过/驳回/撤回/转办/委派)/ServiceTask(委托)/EmbeddedSubProcess/定时边界事件 |
| 二期 | 多实例(会签 sequential/或签 non-sequential)/加签减签/InclusiveGateway/CallActivity/Message/Signal 事件/EventSubProcess |
| 远期 | 补偿/升级/DMN 决策表/表单设计器联动（JSON Schema 表单先顶） |

### 4.3 引擎内核设计要点

1. **命令式内核 + 事件驱动**：所有推进由显式命令触发（start/complete/claim/…），命令走责任链（校验→锁→行为→持久化→事件），无轮询线程。
2. **精简持久化模型**（目标 ≤8 表）：流程定义（含 BPMN XML jsonb）、流程实例、节点实例（token 树）、任务、变量（jsonb）、历史（异步落库）、事件订阅、委托配置。
3. **并发控制**：实例/token 乐观锁（version 列）；水平扩展=服务无状态 + 状态全在 DB，Nacos 多实例天然可用。
4. **灵活度 SPI**：节点行为注册表（BPMN 元素→行为类）、监听器（节点/流程级）、表达式求值（Spring 自带 SpEL 起步，契约阶段裁决是否换 Aviator）。
5. **性能取向**：待办/推进热路径全索引；历史异步写；XML 解析一次缓存为内存模型（部署时编译，运行时零解析）。

### 4.4 任务列表

| ID | 任务 | 内容要点 | 依赖 | 规模 |
|---|---|---|---|---|
| BPM-0 | 预研 ADR（只读，即刻启动） | BPMN 一期语义子集终稿、内核架构（命令/持久化/并发）定稿、X6 vs React Flow 选型终稿、调度中心联动协议 | — | S |
| BPM-1 | 引擎内核 MVP | 服务骨架（双模块+迁移空间+网关路由）+ BPMN XML 解析 + 命令内核 + 一期元素行为 + schema + 集成测试（对照语义测试集） | BPM-0 | L |
| BPM-2 | 流程设计器 | X6 建模器（节点面板/属性面板/连线校验/XML 导入导出/版本对比预览），web 新页面 | BPM-1 | L |
| BPM-3 | 服务 API 面 + 流程管理页 | 定义 CRUD/部署/版本/实例管理（挂起/终止/跳转）/历史查询 API；web 流程管理页（列表/详情/实例监控） | BPM-1 | L |
| BPM-4 | 待办审批中心 | 我的待办/已办/我发起 + 审批操作页（通过/驳回/转办/委派）+ 审批详情（流程图高亮当前节点，复用 X6 只读渲染） | BPM-3 | M~L |
| BPM-5 | 集成 SDK + 试点流程 | framework-workflow client（Feign+本地适配器）+ ServiceTask 委托协议 + 流程事件流；选 1 个业务流程（如请假审批）端到端试点 | BPM-3 | M |
| BPM-6 | 二期高阶语义 | 多实例会签/或签、加签减签、消息/信号事件、定时催办接调度中心 | BPM-5 | L |

## 5. WS-JOB：独立定时任务调度中心（platform-scheduler）

### 5.1 架构总览

- **控制面**（新服务）：任务定义（cron/固定频率/一次性）→ 调度器（**时间轮 + 内存优先队列**，秒级精度）→ 状态全量持久化 PG，集群多实例经选主（复用 ShedLock）单发避免重复触发；misfire 策略（错过补跑/跳过）可配。
- **执行面**：`framework-job` 升级为执行器 SDK——`@JobHandler` 注册 + 执行器应用注册/心跳 + 接收指令（分片上下文 shardIndex/shardTotal）+ 结果上报；存量 `@AcmeTask` 语义平滑迁移。
- **指令通道**：HTTP 直推（低延迟）为主、Kafka（framework-stream）异步兜底为候选，由 JOB-0 ADR 定稿。
- **管理页**：任务管理/执行器监控/执行日志/手动触发，替换现 JobsPage 的后端来源（`/admin/v1/infra/jobs` → `/admin/v1/scheduler/**`，前端 API 迁移兼容）。
- **告警**：失败/超时接通知中心（站内信+WebSocket 已有）。

### 5.2 任务列表

| ID | 任务 | 内容要点 | 依赖 | 规模 |
|---|---|---|---|---|
| JOB-0 | 架构 ADR（只读） | 指令通道定稿、选主方案、misfire/重试策略、与 BPM 定时事件联动协议 | — | S |
| JOB-1 | 调度中心核心 | 服务骨架（双模块+迁移+网关路由 `/admin/v1/scheduler/**`）+ 任务定义模型/CRUD + 时间轮调度器 + 集群选主 + 执行历史 | JOB-0 | L |
| JOB-2 | 执行器 SDK | framework-job 升级：@JobHandler 注册、心跳、指令消费、结果上报、@AcmeTask 平滑迁移 | JOB-1 | L |
| JOB-3 | 管理页 + 存量迁移 | web 任务管理/执行器/日志页（对齐 CrudTable 惯例）；JobsPage 切新 API，单机模式兜底 | JOB-2 | M |
| JOB-4 | 韧性 | 失败重试（次数+退避）、超时控制、分片广播、告警接通知中心 | JOB-2 | M |
| JOB-5 | 高阶 | DAG 依赖编排、事件触发（Kafka 消息驱动）、BPM 定时事件联动实测 | JOB-4 + BPM-5 | L |

## 6. WS-TENANT：租户强化（platform-system）

### 6.1 主流参考要点

- **RuoYi-Vue-Plus / yudao 套餐模型**：租户套餐（package）绑定菜单/功能集合 → 租户绑定套餐 → 租户侧菜单按套餐过滤下发；平台租户（系统租户）不受套餐限制；套餐变更影响存量租户下发。
- **子租户为 ACME 自定义扩展**：主流框架普遍无多级租户，语义需契约阶段钉死（见 §6.3 确认清单）。
- 配额、生命周期（过期/宽限/续期）、开通初始化模板为通用 SaaS 能力，自研对齐。

### 6.2 任务列表

| ID | 任务 | 内容要点 | 依赖 | 规模 |
|---|---|---|---|---|
| TENANT-1 | 租户套餐模型 | `sys_tenant_package` + 套餐-菜单/权限点绑定 + `sys_tenant.package_id`；菜单/权限按套餐过滤下发；平台侧套餐管理页 + 租户管理页套餐分配；迁移号 V24+ | — | M |
| TENANT-2 | 功能分配：服务能力开关 | 「能力」域（workflow/scheduler/文件预览等）注册 + 套餐级/租户级开关；SDK 供各服务鉴权（BPM/调度中心开通位）；迁移号续占 | TENANT-1 | M |
| TENANT-3 | 子租户 | `parent_id` 层级树 + 层级深度限制 + 跨层可见性语义 + 开通授权（平台建 or 套餐授权父租户自助建）——语义按 §6.3 确认后契约化 | TENANT-1 | L |
| TENANT-4 | 生命周期与配额 | 状态机（active/suspended/expired/cancelled + 宽限期续期）；统一配额框架（用户数/流程实例数/调度任务数；文件配额收编进统一模型） | TENANT-1 | M |
| TENANT-5 | 开通模板与运营视图 | 租户开通初始化模板（默认角色/管理员/配置）；平台运营视图（租户用量统计/切换视角） | TENANT-1 | M~L |

### 6.3 契约阶段必答清单（子租户语义）

1. 子租户数据可见性：父租户管理员默认**不可见**子租户数据（推荐），还是可配置穿透？
2. 开通授权：子租户仅平台侧创建（推荐一期），还是套餐允许父租户管理员自助开通（消耗父租户配额）？
3. 层级与配额：最大深度（建议 2~3 层）与配额归集口径（子租户用量计入父租户？）。

## 7. WS-THEME：主题/外观深度定制（vben v5 对照）

### 7.1 vben 能力 → ACME 落地对照

| vben v5 能力 | ACME 落地方案（AntD5） | 分期 |
|---|---|---|
| 主题模式 auto/dark/light（跟随系统） | matchMedia `prefers-color-scheme` + 监听，融入 settings.theme | 一期 |
| 16 内置主题预设 + 自定义取色器 | 强调色从 6 预设扩到 12+ + AntD `ColorPicker` 自定义；色阶/语义色派生保持 wcag 对比度校验（ON_ACCENT 规则保留） | 一期 |
| 语义色覆盖（success/warning/danger） | theme.ts `TONES` 开放覆盖（取色器 + 预设） | 一期 |
| 圆角 radius 可调 | `RADII` token 运行时覆盖档位（小/中/大/全圆） | 一期 |
| 灰度/色弱模式 | 全局 CSS filter（grayscale / 色弱模拟矩阵），作用于内容根节点 | 一期 |
| 内容宽度/密度（contentCompact） | 现密度 2 档扩展 + 内容区宽度（流式/紧凑+宽度值） | 一期 |
| 偏好抽屉（含导出 JSON/重置） | Settings 外观区重构为右上角偏好抽屉（分组：主题/布局/动效/通用），整页设置保留系统类设置 | 一期 |
| 偏好后端持久化 + 业务扩展偏好 | `/admin/v1/system/me/preferences` prefs 扩展 themePreference（schema 校验+版本迁移）；核心+web+mobile 三端消费；扩展机制供后续业务偏好注册 | 一期 |
| 切换动画选项 + 顶部进度条 | 动效开关扩展为动画档位（关闭/简洁/完整）+ 路由切换进度条 | 一期 |
| 布局模式（侧边/顶部/混合等 7 种） | 做 3 种：侧边/顶部/混合（AntD Layout）；ACME 单布局升级 | 二期 |
| 页签样式（chrome/card/plain）+ 拖拽 | tabsStore 已有多页签 → 4 风格 + 拖拽排序 | 二期 |
| 半深色侧栏/顶栏 | 暗色 Neutrals 派生半深色变体 | 二期 |
| 顶栏 widget 开关（通知/搜索/锁屏/刷新等） | 顶栏组件显隐偏好（部分已租户级控制，改个人级叠加） | 二期 |
| 水印/锁屏/i18n 切换 | 已有（水印/锁屏为租户级，保留现状） | — |

### 7.2 任务列表

| ID | 任务 | 内容要点 | 依赖 | 规模 |
|---|---|---|---|---|
| THEME-1 | 偏好引擎 + 偏好抽屉一期 | core 新增 preferences schema（分组/校验/默认值/版本迁移）+ 偏好抽屉 UI（vben 形态）+ 跟随系统 + 取色器 + 预设扩充 | — | M |
| THEME-2 | 外观能力包 | 圆角档位/灰度/色弱/语义色覆盖/内容宽度档位落进偏好抽屉；theme.ts 运行时覆盖机制（不动令牌单源原则） | THEME-1 | M |
| THEME-3 | 偏好后端持久化 + 三端对齐 | me/preferences 扩展（后端校验+msw 同步）；mobile 关键子集对齐（跟随系统/强调色）；扩展偏好注册机制 | THEME-1 | M |
| THEME-4 | 布局与页签二期 | 3 种布局模式 + 页签 4 风格/拖拽 + 半深色侧栏/顶栏 + 顶栏 widget 开关 | THEME-2 | M~L |

## 8. 开发顺序总览

依赖主线：**TENANT-2（能力开关）→ BPM/JOB 服务的租户开通位**；**JOB-1/2 → BPM-6 定时事件联动**；**BPM-1 → 设计器/管理页/待办**。BPM-0 与 JOB-0 为纯只读预研，即刻并行启动，不占开发资源。

| 轮次 | 并行任务 | 里程碑产出 |
|---|---|---|
| 1 | THEME-1 ｜ TENANT-1 ｜ BPM-0 ｜ JOB-0 | 偏好抽屉一期可演示；套餐模型落地；双 ADR 定稿 |
| 2 | THEME-2 ｜ TENANT-2 ｜ JOB-1 ｜ BPM-1 | 服务能力开关就绪；调度中心可调度；引擎内核 MVP |
| 3 | THEME-3 ｜ JOB-2 ｜ BPM-2 | 偏好跨端/跨会话持久化；执行器 SDK；设计器可建模 |
| 4 | JOB-3 ｜ BPM-3 ｜ TENANT-3 | JobsPage 迁移收口；流程可部署可管理；子租户落地 |
| 5 | BPM-4 ｜ JOB-4 ｜ TENANT-4 | 待办审批中心可用；调度具备分片/重试/告警；配额与生命周期 |
| 6 | BPM-5 ｜ TENANT-5 ｜ THEME-4 | SDK 试点流程端到端；开通模板+运营视图；布局/页签二期 |
| 7+ | BPM-6 ｜ JOB-5 ｜ mobile 待办/主题补齐 | 二期高阶语义滚动 |

说明：轮次为「可并行批次」而非串行死排——同轮任务分属不同模块域（web 前端 / platform-system / 新服务 / framework 层），互不阻塞；契约取号时按 §9 预占共享资源即可并行。单人节奏下按轮次顺序执行即为推荐开发顺序。

## 9. 契约化执行与共享资源预占

- **流程**：每个任务 ID 开工前走 team-leader 四阶段（I-REQ 澄清→契约→用户确认→取号建分支预占迁移号；II-DEV 分派 tester/backend/frontend；III-GATE 门禁；III-REVIEW 合流）。大项（BPM-1/BPM-2/JOB-1/TENANT-3）一个 REQ 只圈一个内核交付，必要时拆子 REQ。
- **API 命名空间预占**：`/admin/v1/workflow/**`（platform-workflow）、`/admin/v1/scheduler/**`（platform-scheduler）——服务命名与路径在各自 I-REQ 定稿后即视为占用；与既有 `/admin/v1/infra/jobs` 的迁移关系在 JOB-3 契约钉死。
- **迁移号预占**：platform-system 租户域续占 V24+（当前水位 V23，多 REQ 并行时按取号顺序分配）；workflow/scheduler 各自服务内迁移空间自 V1 起（对齐 platform-system 先例）。
- **前端共享资源**：core `api/workflow.ts`/`api/scheduler.ts`/偏好 schema、msw `handlers/workflow.ts`/`handlers/scheduler.ts`、web 新页面路由（workflow/scheduler/todos）——并行 REQ 取号时在契约 apis 字段登记防漂移。
- **部署清单**（每个新服务契约附）：网关 application.yml 路由+springdoc 分组 → seed-nacos.sh → k8s/base/<svc>.yaml + kustomization → deploy.sh ALL_SERVICES；单体形态 bootstrap 可选聚合作为契约确认项。
- **依赖登记**：`@antv/x6`+`@antv/x6-react-shape` 随 BPM-2 入列 `.ai/rules/project.md`；表达式引擎（SpEL vs Aviator）、cron 前端解析提示库随 BPM-0/JOB-0 ADR 裁决后按需入列。
- **遗留衔接**：差距分析 §6 提示 120107（框架注解接线）收口先行；120108 SSO/120113 E2E 与本规划无冲突，不互斥。
- **验证口径**：后端 `mvn test`（PG 集成测试 Testcontainers）、前端 typecheck/test/build、desktop check；设计器与主题属 UI 密集交付，浏览器实测（browser-use）+ 视觉验收必做，不做「未验证即汇报完成」。

## 附：本文档结论的溯源

- 现状事实：§3 采集自仓库实测（BACKEND.md、acme-parent/pom.xml、framework-tenant、framework-job、platform-system V1/V15/V17 迁移、theme.ts、Settings.tsx、system.ts API、env-stack）。
- vben 能力清单：vben 官方文档（doc.vben.pro/guide/essentials/settings、in-depth/theme）+ vue-vben-admin v5.5.4 源码。
- 主流框架对比：RuoYi(-Vue-Plus)/yudao/JeecgBoot/Snowy/pig、XXL-Job/SnailJob/PowerJob、Flowable/Camunda/Warm-Flow（差距分析 §2/§7 沿用 + ADR 2026-11-21-02）。
