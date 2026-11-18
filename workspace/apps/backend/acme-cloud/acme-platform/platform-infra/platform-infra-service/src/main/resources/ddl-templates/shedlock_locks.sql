-- ShedLock 锁表 DDL 模板（Modules.md 3.5：模板由 platform-infra 维护；自 p4-capability-sample V1 移交）：
-- 使用方服务（framework-job JDBC 模式）在自有 schema 建同名表（12.1.1，M-03 不跨服务共享）；
-- 列宽以 ShedLock 5.16 官方模板为准。
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
