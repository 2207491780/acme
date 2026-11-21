package com.acme.bootstrap.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * 单体共享 DataSource（REQ-20261118-002130 确认③：各服务合并为单库单池——PG，env 键与分布式
 * 完全一致：PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD）。currentSchema 以 search_path 形式
 * 覆盖四 schema（platform_auth,platform_system,platform_infra,platform_scheduler——REQ-20261121-071002
 * 确认⑥增补）：MyBatis 未限定表名按 search_path 依次解析（四 schema 表名无交集，已核对迁移脚本）；
 * Flyway 各实例各自迁移时经 defaultSchema 自行限定连接 search_path（见 MonolithFlywayConfig），
 * 不受本键影响。
 */
@Configuration(proxyBeanMethods = false)
public class MonolithDataSourceConfig {

    /** 四 schema 合并 search_path（次序仅影响同名表解析，当前无同名表）。 */
    public static final String SEARCH_PATH = "platform_auth,platform_system,platform_infra,platform_scheduler";

    @Bean
    public DataSource dataSource(Environment environment) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(environment.getProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getProperty("spring.datasource.username"));
        dataSource.setPassword(environment.getProperty("spring.datasource.password"));
        dataSource.setDriverClassName(environment.getProperty("spring.datasource.driver-class-name",
                "org.postgresql.Driver"));
        return dataSource;
    }
}
