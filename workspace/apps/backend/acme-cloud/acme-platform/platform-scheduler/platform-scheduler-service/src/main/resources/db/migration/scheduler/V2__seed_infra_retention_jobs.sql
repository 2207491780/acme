-- 调度中心 V2（REQ-20261121-200724 确认⑦）：platform-infra 4 个保留期清理任务定义幂等种子。
-- 口径：job_key 沿用现 taskId 值；cron 取现 acme.*.retention-cron 默认值；appName=platform-infra；
-- handler=对应 jobKey；timeout_ms 取执行器侧 @JobHandler 缺省 60000；shard_total=1（本 REQ 单分片语义）。
-- 幂等：NOT EXISTS 守卫——重放不重复；已存在行（含运行态：status/cron/param 被管理面或运维调整）一律
-- 不覆盖。status=DISABLED（创建默认语义对齐 SchedulerJobStatus：种子只入定义，不自动参与调度触发——
-- 启用归运维面/JOB-3 管理页显式决策，同时保全单体形态「调度中心零下发行为」钉死事实）。
-- misfire_policy=FIRE_ONCE_NOW（契约默认）。

-- 1. file-retention-cleanup（acme.storage.upload.retention-cron 默认 每日 03:00）
INSERT INTO sch_job_definition (id, job_key, app_name, handler, trigger_type, cron_expr, fixed_rate_ms,
    once_fire_at, param, retry_policy, shard_total, timeout_ms, misfire_policy, status, next_fire_time,
    tenant_id, remark, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT 20261121000001, 'file-retention-cleanup', 'platform-infra', 'file-retention-cleanup', 'CRON',
    '0 0 3 * * *', NULL, NULL, NULL, NULL, 1, 60000, 'FIRE_ONCE_NOW', 'DISABLED', NULL, NULL,
    'JOB-2 迁移种子：文件保留期清理（cron=acme.storage.upload.retention-cron 默认值；启用归运维面/JOB-3）',
    now(), 'system', now(), 'system', 0, false
WHERE NOT EXISTS (SELECT 1 FROM sch_job_definition WHERE job_key = 'file-retention-cleanup');

-- 2. login-log-retention-cleanup（acme.login-log.retention-cron 默认 每日 03:30）
INSERT INTO sch_job_definition (id, job_key, app_name, handler, trigger_type, cron_expr, fixed_rate_ms,
    once_fire_at, param, retry_policy, shard_total, timeout_ms, misfire_policy, status, next_fire_time,
    tenant_id, remark, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT 20261121000002, 'login-log-retention-cleanup', 'platform-infra', 'login-log-retention-cleanup',
    'CRON', '0 30 3 * * *', NULL, NULL, NULL, NULL, 1, 60000, 'FIRE_ONCE_NOW', 'DISABLED', NULL, NULL,
    'JOB-2 迁移种子：登录日志保留期清理（cron=acme.login-log.retention-cron 默认值；启用归运维面/JOB-3）',
    now(), 'system', now(), 'system', 0, false
WHERE NOT EXISTS (SELECT 1 FROM sch_job_definition WHERE job_key = 'login-log-retention-cleanup');

-- 3. exception-log-retention-cleanup（acme.exception-log.retention-cron 默认 每日 03:40）
INSERT INTO sch_job_definition (id, job_key, app_name, handler, trigger_type, cron_expr, fixed_rate_ms,
    once_fire_at, param, retry_policy, shard_total, timeout_ms, misfire_policy, status, next_fire_time,
    tenant_id, remark, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT 20261121000003, 'exception-log-retention-cleanup', 'platform-infra', 'exception-log-retention-cleanup',
    'CRON', '0 40 3 * * *', NULL, NULL, NULL, NULL, 1, 60000, 'FIRE_ONCE_NOW', 'DISABLED', NULL, NULL,
    'JOB-2 迁移种子：异常日志保留期清理（cron=acme.exception-log.retention-cron 默认值；启用归运维面/JOB-3）',
    now(), 'system', now(), 'system', 0, false
WHERE NOT EXISTS (SELECT 1 FROM sch_job_definition WHERE job_key = 'exception-log-retention-cleanup');

-- 4. audit-retention-cleanup（acme.audit.retention-cron 默认 每日 04:40）
INSERT INTO sch_job_definition (id, job_key, app_name, handler, trigger_type, cron_expr, fixed_rate_ms,
    once_fire_at, param, retry_policy, shard_total, timeout_ms, misfire_policy, status, next_fire_time,
    tenant_id, remark, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT 20261121000004, 'audit-retention-cleanup', 'platform-infra', 'audit-retention-cleanup', 'CRON',
    '0 40 4 * * *', NULL, NULL, NULL, NULL, 1, 60000, 'FIRE_ONCE_NOW', 'DISABLED', NULL, NULL,
    'JOB-2 迁移种子：审计保留期清理（cron=acme.audit.retention-cron 默认值；启用归运维面/JOB-3）',
    now(), 'system', now(), 'system', 0, false
WHERE NOT EXISTS (SELECT 1 FROM sch_job_definition WHERE job_key = 'audit-retention-cleanup');
