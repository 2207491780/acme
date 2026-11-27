package com.acme.platform.system;

import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.test.EmbeddedRedisSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * platform-system 零 Docker 集成测试公共基座：
 * <ul>
 *   <li>同 JVM 内共享 1 个原生嵌入式 PostgreSQL 17 进程 + 1 个原生嵌入式 Redis 进程；</li>
 *   <li>统一 {@code @SpringBootTest} 基线属性，使无特殊 Mock/配置覆盖的 40+ 集成测试类命中同一
 *       Spring {@code ApplicationContext} 缓存；</li>
 *   <li>每当切换测试类时自动在 ~20ms 内将 {@code public} schema 重置回 Flyway 迁移后种子快照，
 *       并清空 Redis，保障跨类 100% 数据隔离且保留同类内用例顺序语义。</li>
 * </ul>
 */
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=system-it-shared-secret-0123456789abcdef"
})
public abstract class AbstractSystemIntegrationTest {

    private static volatile Class<?> lastExecutedClass;

    @DynamicPropertySource
    static void sharedInfrastructure(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        EmbeddedRedisSupport redis = EmbeddedRedisSupport.shared();
        registry.add("spring.datasource.url", () -> pg.jdbcUrlWithSchema("public"));
        registry.add("spring.datasource.username", pg::username);
        registry.add("spring.datasource.password", pg::password);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.data.redis.host", redis::host);
        registry.add("spring.data.redis.port", redis::port);
        registry.add("spring.data.redis.password", () -> "");
    }

    @BeforeEach
    final void resetSharedDatabaseBetweenClasses(TestInfo testInfo) {
        Class<?> current = testInfo.getTestClass().orElse(getClass());
        if (lastExecutedClass != current) {
            EmbeddedPostgresSupport.shared().resetToSnapshot(
                    EmbeddedPostgresSupport.DEFAULT_DATABASE, "public");
            EmbeddedRedisSupport.shared().flushAll();
            lastExecutedClass = current;
        }
    }
}
