-- BPM 引擎内核七表（REQ-20261121-142316 确认②终稿；docs/bpm-engine-design-2026-11-21 §3.3 字段边界
-- 与索引清单）。迁移空间 db/migration/workflow 自 V1（版本号跨服务各自协调）；schema 独占
-- platform_workflow（12.1.1）。审计列对齐 sys_* 惯例（雪花主键禁自增 12.3.1 / 审计填充由框架
-- AuditFillListener 承担 / version 乐观锁 / deleted 软删列对齐 AuditBase 统一口径，恒 false）。
-- 租户隔离：实例/执行/任务/变量/历史/定时 @TenantId；wf_process_definition 平台级共享无 tenant_id
-- （契约确认②：流程定义平台级共享，设计规格 §1）。jsonb 走文本形态（JsonbTypeHandler，
-- platform-system 同款）。列名 key→process_key（保留字安全取正名，其余照设计 §3.3）。
-- bpmn_xml 可空（契约 API：创建草稿 bpmnXml 可选——先建草稿后存 XML；部署前置非空由服务校验）。

-- 1. 流程定义表（平台级）。业务版本列名 deploy_version（设计 §3.3 记名 version）——AuditBase
--    审计口径的乐观锁列固定占名 version（sys_* 同款），业务版本另列承载，语义不变（uk 双列同序）。
CREATE TABLE wf_process_definition (
    id             bigint       PRIMARY KEY,
    process_key    varchar(64)  NOT NULL,
    deploy_version integer      NOT NULL,
    name           varchar(128) NOT NULL,
    bpmn_xml       text,
    status         varchar(16)  NOT NULL,
    layout_json    jsonb,
    deployed_at    timestamptz,
    created_at     timestamptz  NOT NULL,
    created_by     varchar(64)  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    updated_by     varchar(64)  NOT NULL,
    version        bigint       NOT NULL,
    deleted        boolean      NOT NULL,
    CONSTRAINT uk_wf_process_definition_key_version UNIQUE (process_key, deploy_version)
);
CREATE INDEX idx_wf_process_definition_status ON wf_process_definition (status);

-- 2. 流程实例表
CREATE TABLE wf_process_instance (
    id             bigint       PRIMARY KEY,
    tenant_id      varchar(64)  NOT NULL,
    definition_id  bigint       NOT NULL,
    business_key   varchar(128) NOT NULL,
    initiator      varchar(64)  NOT NULL,
    status         varchar(16)  NOT NULL,
    suspend_reason varchar(64),
    start_time     timestamptz  NOT NULL,
    end_time       timestamptz,
    created_at     timestamptz  NOT NULL,
    created_by     varchar(64)  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    updated_by     varchar(64)  NOT NULL,
    version        bigint       NOT NULL,
    deleted        boolean      NOT NULL
);
CREATE INDEX idx_wf_instance_definition_status ON wf_process_instance (definition_id, status);
CREATE INDEX idx_wf_instance_business_key ON wf_process_instance (business_key);
CREATE INDEX idx_wf_instance_initiator_status ON wf_process_instance (initiator, status);

-- 3. 执行树表（token 模型）
CREATE TABLE wf_execution (
    id             bigint       PRIMARY KEY,
    tenant_id      varchar(64)  NOT NULL,
    instance_id    bigint       NOT NULL,
    parent_id      bigint,
    node_id        varchar(64)  NOT NULL,
    node_type      varchar(32)  NOT NULL,
    is_active      boolean      NOT NULL,
    is_concurrent  boolean      NOT NULL,
    is_scope_root  boolean      NOT NULL,
    created_at     timestamptz  NOT NULL,
    created_by     varchar(64)  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    updated_by     varchar(64)  NOT NULL,
    version        bigint       NOT NULL,
    deleted        boolean      NOT NULL
);
CREATE INDEX idx_wf_execution_instance ON wf_execution (instance_id);
CREATE INDEX idx_wf_execution_parent ON wf_execution (parent_id);

-- 4. 任务表
CREATE TABLE wf_task (
    id             bigint       PRIMARY KEY,
    tenant_id      varchar(64)  NOT NULL,
    instance_id    bigint       NOT NULL,
    execution_id   bigint       NOT NULL,
    node_id        varchar(64)  NOT NULL,
    node_name      varchar(128),
    assignee       varchar(64),
    status         varchar(16)  NOT NULL,
    form_data      jsonb,
    due_date       timestamptz,
    claim_time     timestamptz,
    complete_time  timestamptz,
    created_at     timestamptz  NOT NULL,
    created_by     varchar(64)  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    updated_by     varchar(64)  NOT NULL,
    version        bigint       NOT NULL,
    deleted        boolean      NOT NULL
);
CREATE INDEX idx_wf_task_assignee_status ON wf_task (assignee, status);
CREATE INDEX idx_wf_task_instance_status ON wf_task (instance_id, status);

-- 5. 流程变量表
CREATE TABLE wf_variable (
    id           bigint       PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,
    instance_id  bigint       NOT NULL,
    name         varchar(128) NOT NULL,
    value        jsonb,
    created_at   timestamptz  NOT NULL,
    created_by   varchar(64)  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    updated_by   varchar(64)  NOT NULL,
    version      bigint       NOT NULL,
    deleted      boolean      NOT NULL,
    CONSTRAINT uk_wf_variable_instance_name UNIQUE (instance_id, name)
);

-- 6. 历史活动表
CREATE TABLE wf_history_activity (
    id           bigint       PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,
    instance_id  bigint       NOT NULL,
    node_id      varchar(64)  NOT NULL,
    node_type    varchar(32)  NOT NULL,
    action       varchar(16)  NOT NULL,
    assignee     varchar(64),
    start_at     timestamptz  NOT NULL,
    end_at       timestamptz,
    detail       jsonb,
    created_at   timestamptz  NOT NULL,
    created_by   varchar(64)  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    updated_by   varchar(64)  NOT NULL,
    version      bigint       NOT NULL,
    deleted      boolean      NOT NULL
);
CREATE INDEX idx_wf_history_instance_start ON wf_history_activity (instance_id, start_at);

-- 7. 定时任务表
CREATE TABLE wf_timer_job (
    id                bigint       PRIMARY KEY,
    tenant_id         varchar(64)  NOT NULL,
    instance_id       bigint       NOT NULL,
    execution_id      bigint       NOT NULL,
    node_id           varchar(64)  NOT NULL,
    boundary_event_id varchar(64)  NOT NULL,
    due_at            timestamptz  NOT NULL,
    status            varchar(16)  NOT NULL,
    scheduler_job_key varchar(128) NOT NULL,
    created_at        timestamptz  NOT NULL,
    created_by        varchar(64)  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    updated_by        varchar(64)  NOT NULL,
    version           bigint       NOT NULL,
    deleted           boolean      NOT NULL,
    CONSTRAINT uk_wf_timer_job_scheduler_key UNIQUE (scheduler_job_key)
);
CREATE INDEX idx_wf_timer_job_due_status ON wf_timer_job (due_at, status);
