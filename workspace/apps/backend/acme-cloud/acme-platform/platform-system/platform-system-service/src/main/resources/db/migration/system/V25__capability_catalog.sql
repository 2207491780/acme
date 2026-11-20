-- 服务能力开关：能力目录模型（REQ-20261121-054613；幂等口径同 V24：固定主键 + ON CONFLICT (id) DO NOTHING）。
-- 两件 DDL（确认②）：sys_capability 平台级能力目录表（id/capability_code uk/capability_name/status/审计列，
-- 无 tenant_id 平台级——sys_menu/sys_permission 同款 Q-D04 口径）；sys_tenant_package_capability
-- 套餐-能力规范化绑定表（uk(package_id,capability_id)，deleted 恒 false，重绑物理解绑由服务层
-- FlexDeletes 旁路承载，sys_tenant_package_menu 同款 7.2.7 口径）。
-- 一期=套餐级分配（确认②）：租户生效=绑定套餐的能力集；package_id NULL=全部启用能力（V24 既有列）；
-- 租户级个体微调留二期（记录明确）。
-- 无新菜单/权限点/role_permission（确认③：能力目录读取挂既有 system:tenant-package:list 权限，
-- 避免权限水位再膨胀；sys_permission 72 行/sys_menu 49 行水位不变）。
-- 种子（确认③）：能力目录 2 行——1 platform:workflow 工作流 / 2 platform:scheduler 调度中心，均 ENABLED
-- （即规划 WS-BPM/WS-JOB 的租户开通位）；无套餐初始绑定（opt-in）。

-- 1. 能力目录表（平台级，无租户列；status 复用 EnableStatus wire 名，varchar(16) 对齐 sys_permission）
CREATE TABLE sys_capability (
    id              bigint       PRIMARY KEY,
    capability_code varchar(64)  NOT NULL,
    capability_name varchar(64)  NOT NULL,
    status          varchar(16)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    created_by      varchar(64)  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    updated_by      varchar(64)  NOT NULL,
    version         bigint       NOT NULL,
    deleted         boolean      NOT NULL,
    CONSTRAINT uk_sys_capability_code UNIQUE (capability_code)
);

-- 2. 套餐-能力绑定表（规范化绑定表；uk 复合唯一，package_id 前缀查询即绑定集读取）
CREATE TABLE sys_tenant_package_capability (
    id            bigint      PRIMARY KEY,
    package_id    bigint      NOT NULL,
    capability_id bigint      NOT NULL,
    created_at    timestamptz NOT NULL,
    created_by    varchar(64) NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    varchar(64) NOT NULL,
    version       bigint      NOT NULL,
    deleted       boolean     NOT NULL,
    CONSTRAINT uk_sys_tenant_package_capability UNIQUE (package_id, capability_id)
);

-- 3. 能力目录种子（固定主键 1/2 起编，新表无水位冲突；重放幂等空操作）
INSERT INTO sys_capability (id, capability_code, capability_name, status,
                            created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (1, 'platform:workflow',  '工作流',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (2, 'platform:scheduler', '调度中心', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
