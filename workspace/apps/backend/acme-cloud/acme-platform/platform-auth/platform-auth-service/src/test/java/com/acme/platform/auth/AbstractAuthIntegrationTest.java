package com.acme.platform.auth;

import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.test.EmbeddedRedisSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * platform-auth 零 Docker 集成测试公共基座：
 * 共享单 JVM 嵌入式 PostgreSQL 17 + 嵌入式 Redis，类间毫秒级快照还原与 Redis FLUSHALL 隔离。
 */
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=auth-it-shared-security-secret-0123456789abcdef",
        "acme.auth.token.hmac-secret=auth-it-shared-user-secret-0123456789abcdef"
})
public abstract class AbstractAuthIntegrationTest {

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
    final void resetSharedStateBetweenClasses(TestInfo testInfo) {
        Class<?> current = testInfo.getTestClass().orElse(getClass());
        if (lastExecutedClass != current) {
            EmbeddedPostgresSupport.shared().resetToSnapshot(
                    EmbeddedPostgresSupport.DEFAULT_DATABASE, "public");
            EmbeddedRedisSupport.shared().flushAll();
            lastExecutedClass = current;
        }
    }
}
