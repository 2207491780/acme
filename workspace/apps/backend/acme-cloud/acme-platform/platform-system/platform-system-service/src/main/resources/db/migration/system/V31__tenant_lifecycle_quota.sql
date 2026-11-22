-- 租户生命周期宽限期+统一配额框架（REQ-20261122-150802 确认②③；版本预分配 V31——V30 已被
-- TENANT-3 层级占用，禁用 V30 取号）。
-- 三变更（契约 §变更清单 §数据）：
--   sys_tenant.grace_until timestamptz NULL —— 宽限期止点（可空=未配置宽限）。
--     生命周期=保留双态+派生扩展（否决物化方案，无定时任务）：派生态四视觉=启用中
--     （expires 未到或无 expires）/宽限期（expires<now≤grace）/已过期（grace 也过，或
--     expires 过且无 grace）/已停用（status=DISABLED 正交优先）；宽限期内仍可登录（登录面
--     verifyTenantLoginable 派生放行，确认②），续期=既有 update expiresAt 不变（确认⑥）。
--   sys_tenant_package.quota_storage_bytes bigint NULL —— 套餐级存储配额（NULL=继承平台缺省，
--     即 framework-storage StorageProperties.defaultBytes，缺省 1GiB、0=不限；存量不回填）。
--   sys_tenant_quota 新表 —— 租户级配额覆盖（tenant_id uk，逻辑引用 sys_tenant.id 无 FK 对齐
--     库内惯例；quota_storage_bytes NULL=继承套餐，行保留仅置空；0=不限）。一期配额项仅存储
--     （用户数/流程实例/调度任务数=后续 REQ），解析链 TenantQuotaUseCase.resolve 租户覆盖→
--     套餐→平台缺省（确认③）。
-- 审计列对齐 V1 AuditBase 惯例（created_at/created_by/updated_at/updated_by/version/deleted）。
-- 存量缺省不动：既有租户 grace_until=NULL（无宽限）、套餐 quota NULL（继承缺省），无回填。
-- 幂等口径：照 V30 先例语句重放幂等（AC-1 显式钉死执行+重放+幂等，IF NOT EXISTS 承载）。

ALTER TABLE sys_tenant ADD COLUMN IF NOT EXISTS grace_until timestamptz NULL;

ALTER TABLE sys_tenant_package ADD COLUMN IF NOT EXISTS quota_storage_bytes bigint NULL;

CREATE TABLE IF NOT EXISTS sys_tenant_quota (
    id                  bigint       PRIMARY KEY,
    tenant_id           bigint       NOT NULL,
    quota_storage_bytes bigint       NULL,
    created_at          timestamptz  NOT NULL,
    created_by          varchar(64)  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    updated_by          varchar(64)  NOT NULL,
    version             bigint       NOT NULL,
    deleted             boolean      NOT NULL,
    CONSTRAINT uk_sys_tenant_quota_tenant UNIQUE (tenant_id)
);
