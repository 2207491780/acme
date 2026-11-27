package com.acme.platform.infra;

import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.test.EmbeddedRedisSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * platform-infra 零 Docker 集成测试公共基座：
 * 共享单 JVM 嵌入式 PostgreSQL 17 + 嵌入式 Redis + 本地临时存储目录，类间毫秒级快照还原与 Redis FLUSHALL 隔离。
 */
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=infra-it-shared-secret-0123456789abcdef",
        "acme.storage.type=local",
        "acme.storage.local.url-base=http://files.integration.test/acme"
})
public abstract class AbstractInfraIntegrationTest {

    protected static final Path SHARED_STORAGE_ROOT = createStorageRoot();
    private static volatile Class<?> lastExecutedClass;

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("acme-infra-it-storage-");
        } catch (IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

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
        registry.add("acme.storage.local.root-dir", () -> SHARED_STORAGE_ROOT.toString());
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
