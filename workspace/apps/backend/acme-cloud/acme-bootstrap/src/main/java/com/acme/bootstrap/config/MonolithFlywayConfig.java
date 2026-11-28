package com.acme.bootstrap.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 单体手动 Flyway 多实例（REQ-20261118-002130 确认③）：{@code spring.flyway.enabled=false}
 * 关闭 Boot Flyway 自动配置（单 JVM classpath 合流后同路径迁移会互相污染，自动配置单实例
 * 不可用）；本配置类按域各建一个 Flyway——同一 DataSource、schemas/defaultSchema 各自独占、
 * locations 各自子目录（db/migration/{auth|system|infra|scheduler|workflow|leave}）、历史表各自
 * schema 独立（版本号跨服务各自协调——以各迁移目录实际最新版本号为准：本文注释不钉具体号，
 * 防随批次演进陈旧误导；TL 预分配机制维护。当前时点实测：system 至 V37 / infra 至 V19 / auth 至
 * V6 / scheduler 至 V3 / workflow 至 V1 / leave 至 V1；scheduler 增补 REQ-20261121-071002 确认⑥、
 * workflow 增补 REQ-20261121-142316、leave 增补 REQ-20261122-233425 确认⑤）。
 * migrate 经 @Bean initMethod 在各实例就绪时执行（实例间顺序无强依赖：写库互不相交）。
 * auth 域三种子密码 placeholder 保留（与分布式 yml 同键语义，ACME_SEED_* 环境注入）。
 */
@Configuration(proxyBeanMethods = false)
public class MonolithFlywayConfig {

    /** auth 域迁移（schema platform_auth；V1~V6）。 */
    @Bean(initMethod = "migrate")
    public Flyway authFlyway(DataSource dataSource, Environment environment) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("platform_auth")
                .defaultSchema("platform_auth")
                .locations("classpath:db/migration/auth")
                .placeholders(Map.of(
                        "seedSuperadminPassword", requiredSeed(environment, "ACME_SEED_SUPERADMIN_PASSWORD"),
                        "seedDemoAdminPassword", requiredSeed(environment, "ACME_SEED_DEMO_ADMIN_PASSWORD"),
                        "seedDemoOperatorPassword", requiredSeed(environment, "ACME_SEED_DEMO_OPERATOR_PASSWORD")))
                .load();
    }

    /** system 域迁移（schema platform_system；V1~V19）。 */
    @Bean(initMethod = "migrate")
    public Flyway systemFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("platform_system")
                .defaultSchema("platform_system")
                .locations("classpath:db/migration/system")
                .load();
    }

    /** infra 域迁移（schema platform_infra；含 ShedLock 锁表模板；版本号以 db/migration/infra 目录实际为准）。 */
    @Bean(initMethod = "migrate")
    public Flyway infraFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("platform_infra")
                .defaultSchema("platform_infra")
                .locations("classpath:db/migration/infra")
                .load();
    }

    /** scheduler 域迁移（schema platform_scheduler；V1~，REQ-20261121-071002 确认⑥增补）。 */
    @Bean(initMethod = "migrate")
    public Flyway schedulerFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("platform_scheduler")
                .defaultSchema("platform_scheduler")
                .locations("classpath:db/migration/scheduler")
                .load();
    }

    /** workflow 域迁移（schema platform_workflow；V1 七表，REQ-20261121-142316 BPM 引擎聚合增补）。 */
    @Bean(initMethod = "migrate")
    public Flyway workflowFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("platform_workflow")
                .defaultSchema("platform_workflow")
                .locations("classpath:db/migration/workflow")
                .load();
    }

    /** leave 业务域迁移（schema business_leave；V1 请假单，REQ-20261122-233425 确认⑤聚合增补）。 */
    @Bean(initMethod = "migrate")
    public Flyway leaveFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("business_leave")
                .defaultSchema("business_leave")
                .locations("classpath:db/migration/leave")
                .load();
    }

    /** 种子密码必填（14.3.3 口径：无仓库内缺省值——未注入即启动失败；仅首次建库生成凭据）。 */
    private static String requiredSeed(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "单体启动缺少必需环境变量 " + key + "（种子密码不落仓库默认值，14.3.3）");
        }
        return value;
    }
}
