package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
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
 * save 桩镜像 FlexRepositorySupport 语义：更新成功版本 +1 回填实体。
 */
class SystemConfigUseCaseTest {

    private final SysConfigRepository repository = mock(SysConfigRepository.class);
    private final SystemConfigUseCase useCase = new SystemConfigUseCase(repository);

    @Test
    void findCurrentDelegatesToRepository() {
        SysConfig config = new SysConfig(true, 0);
        when(repository.findCurrent()).thenReturn(Optional.of(config));
        assertThat(useCase.findCurrent()).contains(config);
    }

    // ---------- AC-4：无行 upsert（缺省态 version=0） ----------

    @Test
    void updateCreatesRowWhenAbsentAndVersionZero() {
        when(repository.findCurrent()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SysConfig created = useCase.update(new AdminSystemConfigUpdate(false, 60_000, 0L));

        assertThat(created.getWatermarkEnabled()).isFalse();
        assertThat(created.getLockIdleTimeoutMs()).isEqualTo(60_000);
        verify(repository).save(any(SysConfig.class));
    }

    @Test
    void updateRejectsNonZeroVersionWhenAbsent() {
        when(repository.findCurrent()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(new AdminSystemConfigUpdate(false, 60_000, 1L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(WebError.CONCURRENCY_CONFLICT));
        verify(repository, never()).save(any());
    }

    // ---------- AC-2：乐观锁（匹配更新 +1 / 陈旧拒绝） ----------

    @Test
    void updateAppliesNewValuesAndBumpsVersionWhenVersionMatches() {
        SysConfig current = new SysConfig(true, 0);
        current.setVersion(3L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> {
            SysConfig entity = invocation.getArgument(0);
            entity.setVersion(entity.getVersion() + 1);
            return entity;
        });

        SysConfig updated = useCase.update(new AdminSystemConfigUpdate(false, 900_000, 3L));

        assertThat(updated.getWatermarkEnabled()).isFalse();
        assertThat(updated.getLockIdleTimeoutMs()).isEqualTo(900_000);
        assertThat(updated.getVersion()).isEqualTo(4L);
    }

    @Test
    void updateRejectsStaleVersionWithoutTouchingRow() {
        SysConfig current = new SysConfig(true, 0);
        current.setVersion(3L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> useCase.update(new AdminSystemConfigUpdate(false, 60_000, 2L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(WebError.CONCURRENCY_CONFLICT));
        verify(repository, never()).save(any());
        assertThat(current.getWatermarkEnabled()).isTrue();
        assertThat(current.getLockIdleTimeoutMs()).isZero();
    }

    // ---------- AC-4：时长取值集校验（业务失败先于任何持久化访问） ----------

    @Test
    void updateRejectsIdleTimeoutOutsideAllowedSet() {
        for (int invalid : new int[]{123, -1, 60_001, 1_800_000}) {
            assertThatThrownBy(() -> useCase.update(new AdminSystemConfigUpdate(true, invalid, 0L)))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                            .isEqualTo(SystemError.SYSTEM_CONFIG_INVALID));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void updateAcceptsAllAllowedIdleTimeouts() {
        SysConfig current = new SysConfig(true, 0);
        current.setVersion(0L);
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        for (int allowed : new int[]{0, 60_000, 300_000, 900_000}) {
            SysConfig updated = useCase.update(new AdminSystemConfigUpdate(true, allowed, 0L));
            assertThat(updated.getLockIdleTimeoutMs()).isEqualTo(allowed);
        }
    }
}