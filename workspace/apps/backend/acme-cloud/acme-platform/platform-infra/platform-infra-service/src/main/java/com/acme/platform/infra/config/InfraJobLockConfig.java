package com.acme.platform.infra.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock 锁提供方装配（REQ-20261114-071607）：JDBC 模式，锁表 = 本服务 V2 迁移建立的
 * {@code shedlock_locks}（契约 §变更清单表名口径；framework-job 缺省表名为 shedlock，
 * 经显式 Bean 覆盖指向契约表名）。db 时间取自数据库（多实例时钟漂移免疫，官方推荐模式）。
 */
@Configuration
public class InfraJobLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .withTableName("shedlock_locks")
                .build());
    }
}
