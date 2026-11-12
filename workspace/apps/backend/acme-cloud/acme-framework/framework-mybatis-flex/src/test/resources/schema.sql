-- 切片测试隔离环境（15 章测试设计 6）：H2 内存库等价隔离；审计探针表结构对齐 AuditBase 能力面
CREATE TABLE flex_audit_probe (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(64)  NOT NULL,
    amount     BIGINT       NOT NULL,
    seq        BIGINT       NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    updated_by VARCHAR(64)  NOT NULL,
    version    BIGINT       NOT NULL,
    deleted    BOOLEAN      NOT NULL
);
