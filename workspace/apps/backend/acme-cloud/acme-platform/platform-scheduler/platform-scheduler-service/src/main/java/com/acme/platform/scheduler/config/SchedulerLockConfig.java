package com.acme.platform.scheduler.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 调度器选主锁提供方（REQ-20261121-071002 确认⑤：ShedLock JDBC 口径，锁表 sch_scheduler_lock——
 * V1 迁移建表，服务自有 schema 12.1.1，不与其他服务共享）。usingDbTime=以 DB 时钟为准
 * （多实例时钟偏移免疫；ShedLock 5.16 官方口径）。tick 与超时巡检共用本 provider（锁行归属
 * 各自锁名：platform-scheduler-tick / platform-scheduler-patrol）。
 */
@Configuration(proxyBeanMethods = false)
public class SchedulerLockConfig {

    /** 锁表名常量（迁移脚本 DDL 与 provider 配置单源；防漂移仅供运维核对）。 */
    public static final String LOCK_TABLE = "sch_scheduler_lock";

    @Bean
    public LockProvider schedulerLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(LOCK_TABLE)
                        .usingDbTime()
                        .build());
    }
}
