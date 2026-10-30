package com.acme.framework.job.internal;

import com.acme.framework.job.config.JobProperties;
import com.acme.framework.job.config.ShedLockMode;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;

/**
 * ShedLock 锁提供方桥接（FR-025/AC-025，架构设计 10.2）：JDBC/Redis 双模式，AUTO 自选（JDBC 优先）；
 * framework-job 只管装配（BR-019，锁表 DDL 归 platform-infra）。
 */
public final class LockProviderBridges {

    private LockProviderBridges() {
    }

    /** 按模式解析锁提供方（FR-025：互斥语义优先，缺失显式失败）。 */
    public static LockProvider resolve(JobProperties properties,
                                       ObjectProvider<DataSource> dataSource,
                                       ObjectProvider<StringRedisTemplate> redisTemplate) {
        ShedLockMode mode = properties.getShedlock().getMode();
        if (mode == ShedLockMode.JDBC) {
            return jdbc(dataSource);
        }
        if (mode == ShedLockMode.REDIS) {
            return redis(redisTemplate);
        }
        if (dataSource.getIfAvailable() != null) {
            return jdbc(dataSource);
        }
        return redis(redisTemplate);
    }

    /** JDBC 模式（shedlock-provider-jdbc-template）。 */
    public static LockProvider jdbc(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            throw new IllegalStateException("JDBC 模式需要 DataSource（FR-025）");
        }
        return new JdbcTemplateLockProvider(ds);
    }

    /** Redis 模式（shedlock-provider-redis-spring）。 */
    public static LockProvider redis(ObjectProvider<StringRedisTemplate> redisTemplate) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("Redis 模式需要 StringRedisTemplate（FR-025）");
        }
        return new RedisLockProvider.Builder(template).build();
    }
}
