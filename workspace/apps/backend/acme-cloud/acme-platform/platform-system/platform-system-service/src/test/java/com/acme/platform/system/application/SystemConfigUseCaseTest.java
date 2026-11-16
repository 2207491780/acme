package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.TenantPublicInternalView;
import com.acme.platform.system.api.dto.AdminSystemConfigUpdate;
import com.acme.platform.system.domain.SysConfig;
import com.acme.platform.system.infrastructure.repository.SysConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 租户级系统配置用例单元测试（REQ-20261116-230354，纯 JUnit 无 Spring）：
 * AC-2 乐观锁（匹配 +1 / 陈旧拒绝）、AC-4 缺省态 upsert 与时长取值集校验。
 * REQ-20261117-011514：captchaEnabled 开关随 upsert 全量生效、无行缺省视图缺省关闭（AC-4/AC-5 口径）。
 * save 桩镜像 FlexRepositorySupport 语义：更新成功版本 +1 回填实体。
 */
class SystemConfigUseCaseTest {

    private final SysConfigRepository repository = mock(SysConfigRepository.class);
    private final SystemConfigUseCase useCase = new SystemConfigUseCase(repository);

    @Test
    void findCurrentDelegatesToRepository() {
        SysConfig config = new SysConfig(true, false, 0);
        when(repository.findCurrent()).thenReturn(Optional.of(config));
        assertThat(useCase.findCurrent()).contains(config);
    }

    // ---------- AC-4：无行 upsert（缺省态 version=0） ----------

    @Test
    void updateCreatesRowWhenAbsentAndVersionZero() {
        when(repository.findCurrent()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SysConfig created = useCase.update(new AdminSystemConfigUpdate(false, true, 60_000, null, false, 0L));

        assertThat(created.getWatermarkEnabled()).isFalse();
        assertThat(created.getCaptchaEnabled()).isTrue();
        assertThat(created.getLockIdleTimeoutMs()).isEqualTo(60_000);
        verify(repository).save(any(SysConfig.class));
    }

    @Test
    void updateRejectsNonZeroVersionWhenAbsent() {
        when(repository.findCurrent()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(new AdminSystemConfigUpdate(false, false, 60_000, null, false, 1L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(WebError.CONCURRENCY_CONFLICT));
        verify(repository, never()).save(any());
    }

    // ---------- AC-2：乐观锁（匹配更新 +1 / 陈旧拒绝） ----------

    @Test
    void updateAppliesNewValuesAndBumpsVersionWhenVersionMatches() {
        SysConfig current = new SysConfig(true, false, 0);
        current.setVersion(3L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> {
            SysConfig entity = invocation.getArgument(0);
            entity.setVersion(entity.getVersion() + 1);
            return entity;
        });

        SysConfig updated = useCase.update(new AdminSystemConfigUpdate(false, true, 900_000, null, false, 3L));

        assertThat(updated.getWatermarkEnabled()).isFalse();
        assertThat(updated.getCaptchaEnabled()).isTrue();
        assertThat(updated.getLockIdleTimeoutMs()).isEqualTo(900_000);
        assertThat(updated.getVersion()).isEqualTo(4L);
    }

    @Test
    void updateRejectsStaleVersionWithoutTouchingRow() {
        SysConfig current = new SysConfig(true, false, 0);
        current.setVersion(3L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> useCase.update(new AdminSystemConfigUpdate(false, true, 60_000, null, false, 2L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(WebError.CONCURRENCY_CONFLICT));
        verify(repository, never()).save(any());
        assertThat(current.getWatermarkEnabled()).isTrue();
        assertThat(current.getCaptchaEnabled()).isFalse();
        assertThat(current.getLockIdleTimeoutMs()).isZero();
    }

    // ---------- AC-4：时长取值集校验（业务失败先于任何持久化访问） ----------

    @Test
    void updateRejectsIdleTimeoutOutsideAllowedSet() {
        for (int invalid : new int[]{123, -1, 60_001, 1_800_000}) {
            assertThatThrownBy(() -> useCase.update(new AdminSystemConfigUpdate(true, false, invalid, null, false, 0L)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(SystemError.SYSTEM_CONFIG_INVALID));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void updateAcceptsAllAllowedIdleTimeouts() {
        SysConfig current = new SysConfig(true, false, 0);
        current.setVersion(0L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        for (int allowed : new int[]{0, 60_000, 300_000, 900_000}) {
            SysConfig updated = useCase.update(new AdminSystemConfigUpdate(true, false, allowed, null, false, 0L));
            assertThat(updated.getLockIdleTimeoutMs()).isEqualTo(allowed);
        }
    }

    // ---------- REQ-20261117-011514：captchaEnabled 开关与缺省策略视图 ----------

    @Test
    void should_persistCaptchaEnabledSwitch_when_putUpdatesExistingRow() {
        SysConfig current = new SysConfig(true, true, 0);
        current.setVersion(1L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SysConfig updated = useCase.update(new AdminSystemConfigUpdate(true, false, 0, null, false, 1L));

        assertThat(updated.getCaptchaEnabled()).isFalse();
        assertThat(updated.getWatermarkEnabled()).isTrue();
    }

    @Test
    void should_returnDisabledPolicy_when_tenantHasNoConfigRow() {
        when(repository.findByTenantCode("default")).thenReturn(Optional.empty());

        assertThat(useCase.findCaptchaPolicyByTenant("default")).isEqualTo(new CaptchaPolicyView(false));
    }

    @Test
    void should_returnRowPolicyValue_when_tenantHasConfigRow() {
        SysConfig enabled = new SysConfig(true, true, 0);
        when(repository.findByTenantCode("acme")).thenReturn(Optional.of(enabled));

        assertThat(useCase.findCaptchaPolicyByTenant("acme")).isEqualTo(new CaptchaPolicyView(true));
    }

    // ---------- REQ-20261117-025200：页脚版权随 upsert 全量生效与公开视图（AC-8，确认记录⑤） ----------

    @Test
    void should_persistFooterFields_when_putUpdatesExistingRow() {
        // AC-8：PUT config 设置页脚文案 + 显隐 → 保存一致（footerVisible=false 时文案仍随行保存）
        SysConfig current = new SysConfig(true, true, 0);
        current.setVersion(2L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SysConfig updated = useCase.update(new AdminSystemConfigUpdate(true, false, 0,
                "© ACME 2026", true, 2L));

        assertThat(updated.getFooterCopyright()).isEqualTo("© ACME 2026");
        assertThat(updated.getFooterVisible()).isTrue();
    }

    @Test
    void should_createRowWithFooterFields_when_absentAndVersionZero() {
        // 无行 upsert 携带页脚字段：建行即带页脚（缺省可见性 false 语义由请求方决定）
        when(repository.findCurrent()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SysConfig created = useCase.update(new AdminSystemConfigUpdate(true, false, 0,
                "© ACME 2026", true, 0L));

        assertThat(created.getFooterCopyright()).isEqualTo("© ACME 2026");
        assertThat(created.getFooterVisible()).isTrue();
    }

    @Test
    void should_returnDefaultPublicView_when_tenantHasNoConfigRow() {
        // 无行租户公开视图缺省：footerCopyright=null / footerVisible=false（未配置不显示）
        when(repository.findByTenantCode("none")).thenReturn(Optional.empty());

        assertThat(useCase.findPublicViewByTenant("none"))
                .isEqualTo(new TenantPublicInternalView(null, false));
    }

    @Test
    void should_returnRowFooterView_when_tenantHasConfigRow() {
        // 有行租户公开视图按行值（@IgnoreTenant 跨租户读行为由集成测试证明）
        SysConfig configured = new SysConfig(true, true, 0, "© ACME 2026", true);
        when(repository.findByTenantCode("acme")).thenReturn(Optional.of(configured));

        assertThat(useCase.findPublicViewByTenant("acme"))
                .isEqualTo(new TenantPublicInternalView("© ACME 2026", true));
    }

    @Test
    void should_defaultFooterVisibleFalse_when_legacyRowHasNullFlag() {
        // 存量行 footer_visible 列缺省（V16 前直插为 NULL 的防御口径）：公开视图按 false 处理
        SysConfig legacy = new SysConfig(true, true, 0);
        when(repository.findByTenantCode("legacy")).thenReturn(Optional.of(legacy));

        assertThat(useCase.findPublicViewByTenant("legacy"))
                .isEqualTo(new TenantPublicInternalView(null, false));
    }
}
