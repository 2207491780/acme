# BPM 引擎与调度中心纯自研路线（含设计器引擎方向）

日期：2026-11-21
关联：`docs/platform-enhancement-plan-2026-11-21.md`（四工作流规划快照）、`docs/feature-gap-analysis-2026-11-20.md` §5 P1-6/P2-22

## 背景

基座四项增强立项：BPMN 2.0 工作流服务、独立定时任务调度中心（均为单独后端服务）、深度主题定制、租户强化。差距分析原建议「轻量起步选 Warm-Flow / 评估替换 XXL-Job」，但选型确认时用户给出更高目标：流程引擎要求「性能和灵活度最高的 BPMN 2.0」，调度要求「更贴合系统实际设计」，两者均拍板**纯自研**（参考主流开源框架优缺点，不引入现成引擎/调度产品）；流程设计器采用**符合当前技术栈（React18 + AntD5）的主流图编辑引擎自研**，不直接引入 bpmn-js 全家。

## 决定

1. **BPM 引擎纯自研**：新建独立后端服务（建议名 `platform-workflow`，契约阶段定稿）。自研 token 执行内核 + 事件驱动推进 + 精简持久化模型（目标 ≤8 表，对比 Flowable ACT_* 数十张）；**交换层坚持 BPMN 2.0 XML 标准**（导入/导出互操作），语义覆盖分期（一期核心子集：用户/服务任务、排他/并行网关、子流程、定时边界事件；二期：多实例会签或签、消息/信号事件、CallActivity 等）。核心指标=灵活（节点行为 SPI、委托协议全开放）+性能（无轮询、无多数据库兼容包袱）。
2. **调度中心纯自研**：新建独立后端服务（建议名 `platform-scheduler`）。复用现有资产升级：framework-job（@AcmeTask/ShedLock）升级为执行器 SDK，控制面自研（任务定义/时间轮调度/集群选主/注册心跳/执行日志/重试告警分片）；指令通道在 HTTP 直推与 Kafka（framework-stream 已有）间由 JOB-0 ADR 定稿；目标秒级触发精度、多实例无单点。
3. **设计器**：图编辑内核选型方向为 React 生态主流库（推荐 **AntV X6**，备选 React Flow），基于其自研 BPMN 建模器（节点面板/属性面板/XML 导入导出/校验），终稿在 BPM-0 预研 ADR 定案；**不直接嵌入 bpmn-js**（自带建模体系与 React/AntD 融合差，属性面板难贴合现有交互惯例）。
4. 主题/租户两项无引擎之争：分别对照 vben v5 preferences 与 RuoYi-Vue-Plus/yudao 套餐模型自研实现，不引入前端主题库/租户框架。

## 被否的选项

- **Flowable 7.x**：BPMN 2.0 参考级实现、生态成熟，但持久化模型重（历史级别/多库兼容层）、语义裁剪与定制需深入其内核，与「灵活+贴合系统」目标不符；且 Spring Boot 4.1/Java 25 兼容性存疑。用户否决（保留为语义正确性的对照参考与测试集来源）。
- **Camunda 8**：Zeebe 架构性能最强，但需额外 ES/Kafka 重基建、运维成本高、Java Client 模型差异大。
- **Warm-Flow**：轻量国产、审批语义齐全，但仅「类 BPMN」非完整 2.0 规范，与目标有差距。
- **XXL-Job / SnailJob / PowerJob**：管理台虽可独立部署，但调度模型（DB 轮询/内置执行器协议/UI 栈）与 ACME 现有 framework-job 资产、通知中心、租户体系的贴合度不如自研；XXL-Job 精度受 DB 轮询限制。
- **bpmn-js 直接引入**：BPMN 2.0 建模事实标准，但非 React 心智、属性面板与 ACME 表单/权限惯例融合成本高；其建模器能力由 X6/React Flow 自研替代，BPMN 2.0 XML 兼容由自研序列化层保证。

## 影响面

- 新增依赖登记（`.ai/rules/project.md`）：`@antv/x6`（+`@antv/x6-react-shape`）随 BPM-2 契约入列；其余优先复用既有依赖（Spring 自带 `CronExpression`/SpEL，Kafka 通道复用 framework-stream）。
- 新增两个独立后端服务：网关路由 `/admin/v1/workflow/**`、`/admin/v1/scheduler/**`（命名契约阶段定稿）、Nacos 种子、k8s base + deploy.sh 登记；迁移号各服务内自 V1 起，platform-system 租户域从 V24+ 预占。
- platform-system 需先行落地「租户套餐/功能分配-服务能力开关」（TENANT-1/2），为两个新服务提供租户开通位。
- 存量 framework-job 任务与 JobsPage 由 JOB-2/JOB-3 迁移收口，ShedLock 互斥机制保留复用。
