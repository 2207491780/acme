package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261117-011514 sys_config 验证码开关列切片测试（H2 内存库，装配口径同
 * {@link SysConfigRepositorySliceTest}；spring.flyway.enabled=false，表结构来自 test resources
 * schema.sql——后端已按契约同步加列 captcha_enabled）。
 *
 * <p>覆盖：AC-1 的 schema/读写面——新列 captcha_enabled NOT NULL DEFAULT false
 * （省列插入落默认 false、显式 NULL 被约束拒绝）与列值回写往返。</p>
 *
 * <p>口径说明：以 jdbc 直连原始 SQL 断言列语义（不依赖 SysConfig 域构造签名——该签名由并行
 * backend 交付钉死，实体映射面归其自有切片回归）；V16 迁移在真实 PG 上的结构/存量行断言见
 * {@code REQ20261117011514InternalSystemConfigApiTest#should_applyV16CaptchaDefaults_when_migrationApplied}。</p>
 */
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "acme.flex.slow-sql.enabled=false"
})
class REQ20261117011514SysConfigCaptchaSliceTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        // 共享上下文共享同一 H2 库：jdbc 直连物理清空（不经仓储过滤面）
        TenantContext.clear();
        jdbcTemplate.update("delete from sys_config");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ---------- AC-1：新列 NOT NULL DEFAULT false（省列插入落默认） ----------

    @Test
    void should_defaultCaptchaEnabledToFalse_when_rowOmitsColumn() {
        jdbcTemplate.update("""
                insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (990401, 'cap-slice-a', true, 0, current_timestamp, 'req011514-slice',
                        current_timestamp, 'req011514-slice', 0, false)
                """);

        assertThat(jdbcTemplate.queryForObject(
                "select captcha_enabled from sys_config where tenant_id = 'cap-slice-a'", Boolean.class))
                .as("AC-1：省列插入应落列默认值 false（存量写入路径不感知新列也不破约）")
                .isFalse();
    }

    // ---------- AC-1：captcha_enabled 列值读写往返 ----------

    @Test
    void should_roundTripCaptchaEnabled_when_columnUpdated() {
        jdbcTemplate.update("""
                insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms, captcha_enabled,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (990402, 'cap-slice-b', false, 60000, true, current_timestamp, 'req011514-slice',
                        current_timestamp, 'req011514-slice', 0, false)
                """);
        assertThat(readCaptchaEnabled("cap-slice-b")).isTrue();

        jdbcTemplate.update("update sys_config set captcha_enabled = false where tenant_id = 'cap-slice-b'");
        assertThat(readCaptchaEnabled("cap-slice-b")).isFalse();

        jdbcTemplate.update("update sys_config set captcha_enabled = true where tenant_id = 'cap-slice-b'");
        assertThat(readCaptchaEnabled("cap-slice-b")).isTrue();
    }

    // ---------- AC-1：NOT NULL 约束（显式 NULL 拒绝） ----------

    @Test
    void should_rejectNullCaptchaEnabled_when_insertedExplicitly() {
        // NOT NULL 约束由 Spring JDBC 翻译为 DataIntegrityViolationException（H2 23502 → 23000 族）；
        // 若交付翻译口径不同，按 TL 裁决对齐断言类型
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms, captcha_enabled,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (990403, 'cap-slice-c', true, 0, null, current_timestamp, 'req011514-slice',
                        current_timestamp, 'req011514-slice', 0, false)
                """))
                .as("AC-1：captcha_enabled NOT NULL，显式 NULL 不得落库")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Boolean readCaptchaEnabled(String tenantId) {
        return jdbcTemplate.queryForObject(
                "select captcha_enabled from sys_config where tenant_id = ?", Boolean.class, tenantId);
    }
}
