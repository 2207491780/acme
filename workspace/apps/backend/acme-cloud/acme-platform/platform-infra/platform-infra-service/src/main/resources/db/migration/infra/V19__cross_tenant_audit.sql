-- platform-infra 跨租户审计落库表（REQ-20261125-001736 AC-3；Q-005 修订：旁路审计落库）：
-- 列集 = 契约 §验收标准 AC-3（operator/tenant_id/access_point/trace_id/bypass/created_at，
-- 索引 operator+created_at）+ 审计基类口径（framework-flex AuditBase：雪花主键 / created_at /
-- created_by / updated_at / updated_by / version / deleted）。
-- bypass 取值与 acme.tenant.audit 同步日志逐字段对齐：ROOT=旁路开启（tenant_id=*，触及全租户
-- 集合，12.4.2）、false=单租户直读；tenant_id 旁路行记 *（平台级跨租户数据，不启用 @TenantId
-- 租户过滤——落库经 sink 无租户上下文直写，TenantWriteGuard 不拦无租户列实体）。
-- created_at = sink 落库时刻（AuditFillListener 缺省填充，异步尽力落库口径，事件 occurredAt
-- 自包含于 SPI 载体）。
-- 幂等口径：照 system V30/V31 先例语句重放幂等（IF NOT EXISTS 承载）。

CREATE TABLE IF NOT EXISTS sys_cross_tenant_audit (
    id           bigint       PRIMARY KEY,
    operator     varchar(64)  NOT NULL,
    tenant_id    varchar(64)  NOT NULL,
    access_point varchar(256) NOT NULL,
    trace_id     varchar(64)  NOT NULL,
    bypass       varchar(16)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    created_by   varchar(64)  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    updated_by   varchar(64)  NOT NULL,
    version      bigint       NOT NULL,
    deleted      boolean      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sys_cross_tenant_audit_operator_time
    ON sys_cross_tenant_audit (operator, created_at);
