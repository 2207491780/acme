package com.acme.platform.auth;

import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-auth 集成测试（Testcontainers PostgreSQL，16.2.1 本地可执行；无 Docker 自动跳过）：
 * AC-013 实体-脚本一致性（ddl-auto=validate 启动即校验）+ Q-D05 种子凭据 pgcrypto BCrypt
 * 与 Java BCryptPasswordEncoder 兼容性核验（$2a 前缀 + matches）。
 */
class AuthSeedIntegrationTest extends AbstractAuthIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthCredentialRepository credentialRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Test
    void seedCredentialCompatibleWithJavaBcrypt() {
        // AC-013 前置：Flyway V1+V2 已执行（启动即校验通过 = 实体与脚本一致）
        var credential = credentialRepository.findByUserId(1L);
        assertThat(credential).isNotNull();
        String hash = credential.getPasswordHash();
        // Q-D05：pgcrypto crypt(gen_salt('bf',10)) 产出 $2a 前缀 BCrypt，Java 侧 matches 校验通过
        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches("Admin@123456", hash))
                .as("pgcrypto BCrypt 与 spring-security-crypto 兼容（Q-D05 核验登记）").isTrue();
        assertThat(encoder.matches("Wrong@12345", hash)).isFalse();
        assertThat(credential.getTenantId()).isEqualTo("default");
        assertThat(credential.getPasswordUpdatedAt()).isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void seedScriptReplayIsIdempotent() throws Exception {
        // 重放 V2 种子（AC-009 幂等：固定主键 + ON CONFLICT 不重复）
        try (java.io.InputStream in = AuthSeedIntegrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/auth/V2__seed_superadmin_credential.sql")) {
            assertThat(in).isNotNull();
            String seed = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            jdbcTemplate.execute(seed);
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from auth_credential where user_id=1",
                Long.class)).isEqualTo(1L);
    }
}
