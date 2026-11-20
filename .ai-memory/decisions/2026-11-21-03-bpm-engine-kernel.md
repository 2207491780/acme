# BPM 引擎内核与设计器技术定稿（BPM-0 预研交付）

日期：2026-11-21
关联：规格书 `docs/bpm-engine-design-2026-11-21.md`（终稿事实源）、ADR `2026-11-21-02-bpm-job-self-build.md`

## 背景

纯自研 BPMN 2.0 引擎立项后需在动工前钉死：语义子集、执行内核形态、持久化模型、表达式方案、设计器引擎终稿、与调度中心联动协议。选型调研（X6/React Flow/bpmn-js，SpEL/Aviator）与仓库现状（framework-tenant、common-core Result、platform-system 迁移惯例）为输入。

## 决定

- **内核**：命令模式责任链（校验→乐观锁重试→执行→同事务持久化→AFTER_COMMIT 异步事件）+ Execution(token 树) 模型；一期 7 表（definition/instance/execution/task/variable/history_activity/timer_job）；部署时编译为内存模型、运行时零 XML 解析；并发=实例/execution 乐观锁 + 命令退避重试。
- **语义子集**：Start/End/SequenceFlow(条件)/Exclusive/Parallel Gateway/UserTask(通过/驳回/转办/委派/撤回)/ServiceTask(HTTP 委托+重试挂起)/EmbeddedSubProcess/中断型定时边界事件；交换=标准 BPMN 2.0 XML + `acme:` 扩展 namespace。
- **表达式**：SpEL + SimpleEvaluationContext（禁类型/Bean 访问），内置函数白名单注册。
- **设计器**：AntV X6 终稿——`@antv/x6@2` + `@antv/x6-react-shape@3` + 官方 plugin 系列；React 节点 + AntD 属性面板；自研 XML 序列化层（标准语义 + BPMNDI 最小子集 + 布局独立存 layout_json）；校验规则白名单。
- **联动**：定时边界事件经调度中心 ONCE job + HTTP 回调，BPM 侧状态 CAS 幂等；调度中心不可用降级本地 ScheduledExecutorService 兜底。

## 被否的选项

- **React Flow**：更轻，但连线路由/对齐/框选/迷你地图等 BPMN 编辑器必需件需大量自研，X6 开箱即用且 Ant 生态贴合。
- **bpmn-js 直接嵌入**：自带建模体系与 React/AntD 融合差，属性面板交互惯例割裂；其 BPMN 互操作性由自研序列化层达成。
- **Aviator 表达式**：性能更优但引新依赖；一期条件表达式规模小，SpEL+SimpleEvaluationContext 足够且零依赖。
- **Flowable 式多表模型（ACT_*）**：历史级别/多库兼容包袱重；7 表精简模型+异步历史落库满足性能目标。

## 影响面

- 新依赖登记随 BPM-2 契约（x6 系列包）；后端零新依赖。
- BPM-1~6 契约以 `docs/bpm-engine-design-2026-11-21.md` 为规格基线；偏差须回改规格书并在此 ADR 追加记录。
