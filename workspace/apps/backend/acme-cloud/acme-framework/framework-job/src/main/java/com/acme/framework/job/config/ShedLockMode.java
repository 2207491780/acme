package com.acme.framework.job.config;

/**
 * ShedLock 装配模式（FR-025、架构设计 10.2）。
 */
public enum ShedLockMode {

    /** 按类路径与数据源自选（JDBC 优先，回落 Redis）。 */
    AUTO,

    /** JDBC 模式（shedlock-provider-jdbc-template）。 */
    JDBC,

    /** Redis 模式（shedlock-provider-redis-spring）。 */
    REDIS
}
