# ACME BPMN 2.0 工作流引擎设计规格（platform-workflow）

> 定稿日期：2026-11-21 ｜ 任务锚点：规划文档 WS-BPM（BPM-0 交付物）
> 上游决策：ADR `2026-11-21-02-bpm-job-self-build.md`（纯自研路线）；本文档 = 内核/语义/设计器/联动协议的终稿事实源。
> 后续契约（BPM-1~6）以本文档为规格基线；实现偏差须回改本文档并留 ADR 记录。

## 1. 服务布局

| 项 | 定稿 |
|---|---|
| 模块 | `acme-platform/platform-workflow/`：`platform-workflow-api`（DTO + Feign 契约）＋ `platform-workflow-service`（实现） |
| 端口 | 9883（infra 9880 / system 9881 / auth 9882 之后，gateway 9888 之前） |
| 网关路由 | `/admin/v1/workflow/**` → `lb://platform-workflow`；springdoc 分组 `/v3/api-docs/workflow` |
| 迁移空间 | `db/migration/workflow/` 自 V1 起（对齐 platform-system 先例） |
| 部署 | k8s base `platform-workflow.yaml` + kustomization + deploy.sh `ALL_SERVICES` + seed-nacos.sh 种子；bootstrap 单体可选聚合（本地短路适配器对齐 LocalTenantClient 惯例） |
| 租户隔离 | 业务表统一 `@TenantId` + TenantContext（framework-tenant）；流程定义平台级共享、实例租户级隔离 |

## 2. BPMN 2.0 一期语义子集（终稿）

交换格式 = 标准 BPMN 2.0 XML（namespace `http://www.omg.org/spec/BPMN/20100524/MODEL`）；ACME 扩展属性挂独立 namespace `http://acme.dev/bpmn/ext/v1`（前缀 `acme:`），不污染标准语义。

| 元素 | 一期语义裁定 |
|---|---|
| Process | 顶层容器；key 全局唯一，版本自增（同 key 部署 → version+1） |
| StartEvent | none 类型；每个 Process 恰一个 |
| EndEvent | none 类型；到达即实例终止（COMPLETED） |
| SequenceFlow | 可带 `conditionExpression`（SpEL，见 §5.5）；网关流出必须带条件（default flow 除外） |
| ExclusiveGateway | XOR 分流：取第一个条件为真的流出（无真 → default flow；无 default → 引擎抛no-route 异常并挂起该 execution） |
| ParallelGateway | AND 分流 fork N 个并发 execution；汇合 join 按到达计数，全部到达后聚合继续（快照计数，禁动态加入） |
| UserTask | 指派规则（见下）；操作：complete（通过）/ reject（驳回）/ claim / unclaim / transfer（转办）/ delegate（委派）/ 撤回（发起人） |
| ServiceTask | HTTP 委托：`acme:serviceRef`（目标服务名）+ `acme:callbackPath`（内部端点）+ 超时/重试配置；回调携带 instanceId/executionId/变量快照；回调成功 → 推进，失败 → 按 `acme:retryPolicy` 重试后挂起实例（SUSPENDED 原因=委托失败） |
| EmbeddedSubProcess | 嵌入子流程：独立 scope（execution 子树）；子流程变量遮蔽父流程同名变量（读穿透、写不穿透） |
| BoundaryEvent(timer, interrupting) | 挂在 UserTask/EmbeddedSubProcess 上；到时中断当前任务并转入边界流出分支；非中断型二期 |
| CallActivity / InclusiveGateway / 多实例 / Message / Signal / EventSubProcess / 补偿 | 二期（BPM-6） |

**UserTask 指派规则**（`acme:` 扩展属性，按序生效）：
- `acme:assignee`：固定用户 id / `${initiator}` / SpEL 变量
- `acme:candidateRoles`：角色 code 列表（逗号分隔）→ 候选组
- `acme:candidateDepts`：部门 id 列表 → 候选组
- `acme:candidateUsers`：用户 id 列表
- 候选语义：候选人 claim 后成为 assignee；assignee 已定则跳过候选；`acme:assigneeStrategy`（一期仅 ALL——全部候选可见可办）

**驳回语义（终稿）**：`reject(targetNodeId?)`——targetNodeId 缺省 = 回退到上一个已完成 UserTask；指定 = 回退到任意历史 UserTask/StartEvent（发起新一轮）。驳回产生新任务实例，历史链保留（wf_history_activity 记 REJECTED 边）。发起人撤回 = `terminate`（实例置 TERMINATED，未完成 execution/task 全部 CANCELLED）。

## 3. 执行内核架构

### 3.1 命令模式（所有状态变更唯一入口）

```
WorkflowCommand<R> ──> CommandInterceptor 责任链：
  CommandContextInit → OptimisticLockRetry(≤3, 指数退避 50/100/200ms)
  → Validation（状态机前置校验）→ Execution（节点行为）→ Persistence（同事务落库）
  → EventPublish（AFTER_COMMIT 异步：历史落库 + Kafka 事件流）
```
- 事务边界 = 单命令一事务（Spring @Transactional 命令包装器）；命令失败整体回滚，无部分推进。
- 只读查询（待办/历史/详情）不走命令链，直接 service 查询。

### 3.2 Execution（token）模型

- `ProcessInstance(1) : Execution(N)` 树形（parent_id）；execution = 一个移动中的 token，停在某个 node。
- 并行网关 fork：创建 N 个 child execution（is_concurrent=true）；join：child 到达 join 网关即挂起计数，最后一个到达者聚合（其余 child 标记 consumed），从 parent execution 继续。
- 子流程 scope：subprocess 内 execution 为其 entry execution 的子树；子流程结束回收子树。

### 3.3 持久化模型（一期 7 表，DDL 细节随 BPM-1 契约，此处定字段边界）

| 表 | 关键列 | 索引 |
|---|---|---|
| wf_process_definition | key, version, name, bpmn_xml(TEXT), status(DRAFT/DEPLOYED/DISABLED), tenant_id | uk(key,version)；idx(status) |
| wf_process_instance | definition_id, business_key, initiator, status(RUNNING/SUSPENDED/COMPLETED/TERMINATED), suspend_reason, start_time, end_time, version | idx(definition_id,status)；idx(business_key)；idx(initiator,status) |
| wf_execution | instance_id, parent_id, node_id, node_type, is_active, is_concurrent, is_scope_root, version | idx(instance_id)；idx(parent_id) |
| wf_task | instance_id, execution_id, node_id, node_name, assignee, status(CREATED/CLAIMED/COMPLETED/REJECTED/CANCELLED), form_data(jsonb), due_date, claim_time, complete_time | idx(assignee,status)；idx(instance_id,status) |
| wf_variable | instance_id, name, value(jsonb) | uk(instance_id,name) |
| wf_history_activity | instance_id, node_id, node_type, action(ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE), assignee, start_at, end_at, detail(jsonb) | idx(instance_id,start_at) |
| wf_timer_job | instance_id, execution_id, node_id, boundary_event_id, due_at, status(PENDING/FIRED/CANCELLED), scheduler_job_key | uk(scheduler_job_key)；idx(due_at,status) |

- 审计列（created_by/created_at/updated_at）对齐 `sys_*` 既有惯例。
- 乐观锁：wf_process_instance.version / wf_execution.version（mybatis-flex `@Column(version = true)`）。
- 变量一期仅 instance 级；jsonb 值（字符串/数值/布尔/日期/对象均 JSON 序列化，读回按注册类型还原）。

### 3.4 部署时编译与运行时零解析

- 部署（POST /process-definitions/deploy）：XML 结构校验（§6 校验规则）→ 解析为 `CompiledProcessModel`（element map + 流出表 + 行为类绑定 + 表达式预解析为 SpEL AST）→ 缓存 `ConcurrentHashMap<definitionId, CompiledProcessModel>`（DISABLED/新版本部署即失效）→ 运行时零 XML 解析。
- 引擎启动不预加载全量；首次访问加载 + 缓存。

### 3.5 表达式求值

- SpEL + `SimpleEvaluationContext`（仅变量 map + 自定义函数绑定；禁类型引用/Bean 访问，安全红线）。
- 内置函数注册表（静态工具方法绑定）：`hasRole('x')`、`inDept('x')`、`var('name')`、日期比较工具——按需在 BPM-1 契约列清单，不预留任意 eval。

### 3.6 SPI 扩展点（灵活度主张的落点）

| 扩展点 | 形态 |
|---|---|
| FlowNodeBehavior | `Map<nodeType, FlowNodeBehavior>` 注册表（Spring bean 收集）；BPMN 标准行为为内置实现，业务可注册覆盖 |
| ExecutionListener | 节点级 `acme:executionListeners`（before/after），一期实现形式 = 固定事件发 Kafka，自定义类加载二期 |
| TaskListener | 任务创建/完成/驳回钩子，同上事件化 |
| 委托解析器 | ServiceTask `serviceRef` → 目标 URL 的解析策略（默认 Nacos 服务名 + 配置路径模板） |

## 4. 服务 API 面（BPM-3 细化，此处定资源边界）

- 定义：`POST /admin/v1/workflow/process-definitions`（创建草稿）/ `PUT .../{id}/xml`（保存 XML）/ `POST .../{id}/deploy` / `GET .../{id}` / `GET ...?status=`（分页）/ `POST .../{id}/disable`
- 实例：`POST /admin/v1/workflow/process-instances`（启动：definitionId+businessKey+variables）/ `GET .../{id}`（详情：实例+当前节点+变量+任务树）/ `GET ...?businessKey=` / `POST .../{id}/terminate` / `POST .../{id}/suspend` | `resume`
- 任务：`GET /admin/v1/workflow/tasks/todo`（我的待办，分页）/ `GET .../done` / `GET .../initiated`（我发起）/ `POST .../{taskId}/complete` | `reject` | `claim` | `unclaim` | `transfer` | `delegate`
- 历史：`GET /admin/v1/workflow/process-instances/{id}/history`（节点历史链）
- 内部（服务间，不经网关）：`POST /internal/v1/workflow/timers/{timerJobId}/fire`（调度中心回调）、`POST /internal/v1/workflow/service-tasks/{executionId}/callback`（业务委托回调）
- 权限点：WORKFLOW_LIST / WORKFLOW_DEPLOY / WORKFLOW_TERMINATE / TASK_TODO 等对齐 BUTTON+API 权限体系；错误码命名空间 `WORKFLOW_*`（common-core Result 语义，业务失败 code≠0 + HTTP 200/4xx 对齐现有翻译器行为）。

## 5. 设计器（AntV X6，终稿）

- **依赖**：`@antv/x6@2` + `@antv/x6-react-shape@3` + 插件包 `@antv/x6-plugin-selection` / `-transform` / `-snapline` / `-clipboard` / `-keyboard` / `-minimap`（与 X6 2.x 对齐；入 `.ai/rules/project.md` 登记清单）。
- **结构**（web 新页面 `/workflow/designer/:definitionId?`）：
  - 画布：X6 Graph + 网格/对齐线/框选；节点=React 组件（x6-react-shape）按 BPMN 类型分渲染；连线正交路由 + 连接校验（`validateConnection`：目标类型白名单、无自环、start 无入边、end 无出边、gateway 出边≥2）。
  - 左侧 Palette：按一期元素清单分组拖拽（Dnd）。
  - 右侧 PropertyPanel：AntD 表单——节点通用（id/name）+ 类型特有（指派规则、条件表达式、委托配置、定时器）；表单校验即时回写节点数据。
  - 工具栏：保存草稿（XML+布局 json）/ 校验 / 导入 BPMN XML / 导出 / 版本对比（两版本 XML diff 只读预览，BPM-2 一期可降级为双栏文本 diff）。
- **序列化层**（自研，core 包 `workflow/bpmn-io.ts`）：
  - export：CompiledModel → 标准 BPMN 2.0 XML（process 语义完整）+ BPMNDI 最小子集（每个节点/边一个 shape/edge + bounds，保证 bpmn.io 可打开）+ `acme:` 扩展属性。
  - import：标准 XML 解析（DOMParser，浏览器端）→ 一期白名单校验 → 无 DI 时分层自动布局（拓扑层级 y 坐标 + 同层 x 间距）。
  - 图形布局（节点坐标）独立存 `wf_process_definition` 新列 `layout_json`（jsonb）——语义与布局分离，回导不丢布局。
- **校验规则**（保存/部署前置）：start/end 恰一、无悬挂连线、node id 唯一且非保留字、gateway 条件完备（XOR 无 default 时全边带条件）、UserTask 至少一种指派规则、ServiceTask 必填 serviceRef/callbackPath、BoundaryEvent 必须挂接点、SpEL 语法预解析通过。
- 性能：节点数 ≤500 规模目标；渲染用 x6 async:true + 虚拟化不启用（规模内无需）；属性面板编辑防抖回写。

## 6. 与调度中心联动协议（对齐 scheduler-center-design §7）

- 注册：边界事件到达 → BPM 落 `wf_timer_job(PENDING, due_at)` → 调 scheduler 内部 API `POST /internal/v1/scheduler/jobs`（jobKey=`wf-timer:{timerJobId}`，trigger=once@due_at，callback={service: platform-workflow, path: /internal/v1/workflow/timers/{timerJobId}/fire}）。
- 触发：scheduler 回调 fire → BPM 校验 timer PENDING + execution active → 标 FIRED → 中断当前任务（CANCELLED）→ 走边界流出分支推进。
- 注销：任务完成/实例终止/驳回离开该节点 → `DELETE /internal/v1/scheduler/jobs/{jobKey}` + wf_timer_job 置 CANCELLED。
- 降级：scheduler 不可达 → 注册进本地 `ScheduledExecutorService` 兜底队列 + 后台重试注册（成功后移除本地）；保证 timer 语义不因部署顺序丢失。
- 幂等：fire 回调带 scheduler executionId，BPM 侧 uk(scheduler_job_key) + 状态 CAS 保证重复 fire 只生效一次。

## 7. 集成模型（BPM-5 落地）

1. **SDK**：`framework-workflow-client`（acme-framework 新模块）：Feign 接口对齐 §4 API（启动/推进/查待办）+ bootstrap 本地短路适配器；业务服务最小接入 = 引依赖 + 配置 serviceRef。
2. **ServiceTask 委托**：BPM → 目标业务服务 `POST {callbackPath}`（携带 executionId/变量快照/traceId）；业务完成后回调 §4 internal callback 推进；失败重试按 `acme:retryPolicy`（次数+退避），耗尽 → 实例 SUSPENDED（reason=SERVICE_TASK_FAILED）+ 告警事件。
3. **流程事件流**：实例启动/完成/终止、节点到达/离开、任务创建/完成 → Kafka topic `acme-workflow-events`（framework-stream），payload 含 tenantId/definitionKey/instanceId/nodeId/eventType/variables 摘要；通知中心/审计可消费。

## 8. 测试策略（BPM-1 起执行）

- 语义测试集（对照 BPMN 规范 + Flowable 常见场景）：排他网关全分支/default 兜底/无条件异常；并行 fork-join 全到达聚合、部分到达不推进；驳回链（默认回退/指定回退/发起人撤回）；子流程变量遮蔽与回收；定时边界中断（mock scheduler 回调）；ServiceTask 委托成功/失败/重试耗尽挂起；并发 claim/complete CAS 冲突重试；乐观锁竞争（两实例同时推进同 token）。
- 基建：Testcontainers PG（对齐 platform-system 集成测试惯例）；设计器前端 vitest（序列化层单测——XML↔模型往返、校验规则、自动布局）+ 浏览器实测。

## 9. 依赖登记汇总

- 后端：零新增（SpEL/CronExpression/异步全用 Spring 自带；Kafka 走 framework-stream）。
- 前端：`@antv/x6@2`、`@antv/x6-react-shape@3`、`@antv/x6-plugin-selection/-transform/-snapline/-clipboard/-keyboard/-minimap`（随 BPM-2 契约登记 `.ai/rules/project.md`）。
