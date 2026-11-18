package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.flex.support.FlexError;
import com.acme.platform.system.application.SystemConfigUseCase;
import com.acme.platform.system.domain.SysConfig;
import com.acme.platform.system.infrastructure.repository.SysConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户级系统配置仓储切片测试（REQ-20261116-230354，H2 内存库 + MyBatis-Flex 真实 SQL 面，
 * 装配口径同 {@link FlexRepositorySliceTest}）：AC-1 租户隔离（@TenantId 自动过滤）、
 * AC-2 乐观锁底座（版本 +1 / 陈旧 0 行转 STALE_WRITE）、审计与租户列填充。
 * REQ-20261117-011514：captcha_enabled 列缺省 false；按租户代码直查经用例 @IgnoreTenant 旁路生效。
 */
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "acme.flex.slow-sql.enabled=false"
})
class SysConfigRepositorySliceTest {

    @Autowired
    SysConfigRepository configRepository;

    @Autowired
    SystemConfigUseCase systemConfigUseCase;

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

    // ---------- AC-1：租户隔离（每租户一行，互不可见） ----------

    @Test
    void findCurrentReturnsOnlyOwnTenantRow() {
        TenantContext.bind(new TenantContext("cfg-a"));
        configRepository.save(new SysConfig(false, false, 60_000));
        TenantContext.bind(new TenantContext("cfg-b"));
        configRepository.save(new SysConfig(true, true, 900_000));

        TenantContext.bind(new TenantContext("cfg-a"));
        SysConfig a = configRepository.findCurrent().orElseThrow();
        assertThat(a.getTenantId()).isEqualTo("cfg-a");
        assertThat(a.getWatermarkEnabled()).isFalse();
        assertThat(a.getCaptchaEnabled()).isFalse();
        assertThat(a.getLockIdleTimeoutMs()).isEqualTo(60_000);
        assertThat(configRepository.findAll()).hasSize(1);

        TenantContext.bind(new TenantContext("cfg-b"));
        SysConfig b = configRepository.findCurrent().orElseThrow();
        assertThat(b.getTenantId()).isEqualTo("cfg-b");
        assertThat(b.getWatermarkEnabled()).isTrue();
        assertThat(b.getCaptchaEnabled()).isTrue();
        assertThat(b.getLockIdleTimeoutMs()).isEqualTo(900_000);
        assertThat(configRepository.findAll()).hasSize(1);

        // 库内两行并存（jdbc 直连不经租户过滤面）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_config", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void findCurrentEmptyWhenTenantHasNoRow() {
        TenantContext.bind(new TenantContext("cfg-a"));
        configRepository.save(new SysConfig(true, false, 0));
        TenantContext.bind(new TenantContext("cfg-empty"));
        assertThat(configRepository.findCurrent()).isEmpty();
    }

    // ---------- 保存：审计 + 租户 + 版本填充 ----------

    @Test
    void saveInsertFillsAuditTenantAndVersion() {
        TenantContext.bind(new TenantContext("cfg-a"));
        SysConfig saved = configRepository.save(new SysConfig(true, false, 0));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo("cfg-a");
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getCreatedBy()).isEqualTo("system");
        assertThat(saved.getDeleted()).isFalse();
    }

    // ---------- AC-2 底座：乐观锁版本 +1 / 陈旧写拒绝 ----------

    @Test
    void saveUpdateBumpsVersionInEntityAndDatabase() {
        TenantContext.bind(new TenantContext("cfg-a"));
        SysConfig saved = configRepository.save(new SysConfig(true, false, 0));
        saved.setWatermarkEnabled(false);
        saved.setLockIdleTimeoutMs(60_000);
        SysConfig updated = configRepository.save(saved);
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select version from sys_config where tenant_id='cfg-a'", Long.class)).isEqualTo(1L);
    }

    @Test
    void saveWithStaleVersionThrowsStaleWrite() {
        TenantContext.bind(new TenantContext("cfg-a"));
        SysConfig saved = configRepository.save(new SysConfig(true, false, 0));
        saved.setWatermarkEnabled(false);
        configRepository.save(saved);
        // 库中 version=1；实体版本回拨为 0 → 0 行 → STALE_WRITE
        saved.setVersion(0L);
        assertThatThrownBy(() -> configRepository.save(saved))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(FlexError.STALE_WRITE));
    }

    // ---------- REQ-20261117-011514：captcha_enabled 列缺省与跨租户策略直查 ----------

    @Test
    void should_defaultCaptchaEnabledToFalse_when_rowInsertedWithoutColumn() {
        // 迁移缺省口径（AC-1）：存量语义的插入（不带新列）落库后 captcha_enabled=false
        TenantContext.bind(new TenantContext("cfg-legacy"));
        jdbcTemplate.update("""
                insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (990001, 'cfg-legacy', true, 0, now(), 'slice', now(), 'slice', 0, false)
                """);
        SysConfig legacy = configRepository.findCurrent().orElseThrow();
        assertThat(legacy.getCaptchaEnabled()).isFalse();
        assertThat(systemConfigUseCase.findCaptchaPolicyByTenant("cfg-legacy").captchaEnabled()).isFalse();
    }

    @Test
    void should_findRowByTenantCodeAcrossTenants_when_useCaseBypassesTenantFilter() {
        TenantContext.bind(new TenantContext("cfg-a"));
        configRepository.save(new SysConfig(true, true, 0));
        TenantContext.bind(new TenantContext("cfg-b"));
        configRepository.save(new SysConfig(false, false, 0));

        // 会话租户 cfg-b 查询 cfg-a 策略（@IgnoreTenant 旁路）：tenant_id 列存租户代码，直查命中
        assertThat(systemConfigUseCase.findCaptchaPolicyByTenant("cfg-a").captchaEnabled()).isTrue();
        assertThat(systemConfigUseCase.findCaptchaPolicyByTenant("cfg-b").captchaEnabled()).isFalse();
        // 无行租户返回缺省关闭
        assertThat(systemConfigUseCase.findCaptchaPolicyByTenant("cfg-missing").captchaEnabled()).isFalse();
    }

    // ---------- REQ-20261117-180000：watermark_template 列往返与存量缺省 ----------

    @Test
    void should_roundTripWatermarkTemplate_when_saveAndReload() {
        TenantContext.bind(new TenantContext("cfg-tpl"));
        SysConfig saved = configRepository.save(
                new SysConfig(true, false, 0, null, false, "{username} {date} {time}"));
        assertThat(saved.getWatermarkTemplate()).isEqualTo("{username} {date} {time}");

        SysConfig reloaded = configRepository.findCurrent().orElseThrow();
        assertThat(reloaded.getWatermarkTemplate()).isEqualTo("{username} {date} {time}");

        reloaded.setWatermarkTemplate("{tenantCode}-{userId}");
        configRepository.save(reloaded);
        assertThat(configRepository.findCurrent().orElseThrow().getWatermarkTemplate())
                .isEqualTo("{tenantCode}-{userId}");

        reloaded.setWatermarkTemplate(null);
        configRepository.save(reloaded);
        assertThat(configRepository.findCurrent().orElseThrow().getWatermarkTemplate()).isNull();
    }

    @Test
    void should_defaultWatermarkTemplateNull_when_legacyRowInsertedWithoutColumn() {
        // 存量语义插入（不带新列）：watermark_template=NULL → 未配置（前端仅用户名）
        TenantContext.bind(new TenantContext("cfg-tpl-legacy"));
        jdbcTemplate.update("""
                insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (990002, 'cfg-tpl-legacy', true, 0, now(), 'slice', now(), 'slice', 0, false)
                """);
        assertThat(configRepository.findCurrent().orElseThrow().getWatermarkTemplate()).isNull();
    }
}
