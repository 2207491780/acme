-- 切片测试隔离环境（15 章测试设计 6）：H2 内存库等价隔离；租户探针表结构对齐 12.4.1 租户归属实体
CREATE TABLE tenant_probe (
    id         BIGINT PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    updated_by VARCHAR(64)  NOT NULL,
    version    BIGINT       NOT NULL,
    deleted    BOOLEAN      NOT NULL
);
