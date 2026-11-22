-- 分片广播真扇出 + 定义扇出开关列（REQ-20261122-150756 确认③ + shardable 载体裁决⑥；
-- 版本预分配 V3——V1 建表 / V2 种子已占号，禁自行取号）。
-- ① sch_job_definition 加列 shardable boolean NOT NULL DEFAULT false：分片扇出权威开关
--    （载体裁决⑥：调度侧列=扇出开关，SDK @JobHandler.shardable() 为 advisory）。
--    PG ADD COLUMN NOT NULL DEFAULT 语义下全量既有行即得 false，存量默认单发兼容无损（AC-6），
--    无需独立 UPDATE 回填。
-- ② 触发幂等部分唯一索引重建：uk(def_id,fire_time) → uk(def_id,fire_time,shard_index)，
--    谓词不变 WHERE trigger_type='SCHEDULE' AND attempt=0（确认③：每周期每片至多一条首发
--    execution；单分片 shard_index 恒 0 行为不变，MANUAL/RETRY/EVENT 不受约束）。
--    重建取 DROP IF EXISTS + CREATE 口径并注缘由（照 system V30 幂等重放纪律，AC-6 显式钉死
--    「重放幂等+存量无损」：IF NOT EXISTS 无法把既有索引列序扩到 shard_index，必须先 DROP 再
--    CREATE；旧索引已保证 (def_id,fire_time) 唯一 ⇒ 新索引重建无唯一冲突风险，数据零改写）。

ALTER TABLE sch_job_definition ADD COLUMN IF NOT EXISTS shardable boolean NOT NULL DEFAULT false;

DROP INDEX IF EXISTS uk_sch_execution_schedule_fire;
CREATE UNIQUE INDEX uk_sch_execution_schedule_fire
    ON sch_job_execution (definition_id, fire_time, shard_index)
    WHERE trigger_type = 'SCHEDULE' AND attempt = 0;
