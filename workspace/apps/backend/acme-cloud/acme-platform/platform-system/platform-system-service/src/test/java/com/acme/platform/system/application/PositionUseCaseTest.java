package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminPositionCreate;
import com.acme.platform.system.api.dto.AdminPositionUpdate;
import com.acme.platform.system.domain.SysPosition;
import com.acme.platform.system.infrastructure.repository.SysPositionRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 岗位管理用例测试（REQ-20261114-120104，AC-2：编码租户内唯一、被引用删除拒绝、乐观锁）。
 */
class PositionUseCaseTest {

    private final SysPositionRepository positionRepository = mock(SysPositionRepository.class);
    private final SysUserRepository userRepository = mock(SysUserRepository.class);

    private final PositionUseCase useCase = new PositionUseCase(positionRepository, userRepository);

    @Test
    void createDuplicatedCodeRejected() {
        when(positionRepository.existsByPositionCode("engineer")).thenReturn(true);
        assertThatThrownBy(() -> useCase.create(new AdminPositionCreate("engineer", "工程师", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.POSITION_CODE_DUPLICATED.code()));
    }

    @Test
    void createSavesPositionWithDefaultStatus() {
        when(positionRepository.existsByPositionCode("engineer")).thenReturn(false);
        when(positionRepository.save(any(SysPosition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SysPosition saved = useCase.create(new AdminPositionCreate("engineer", "工程师", null));
        assertThat(saved.getStatus()).isEqualTo(EnableStatus.ENABLED);
    }

    @Test
    void deleteReferencedByUserRejected() {
        // AC-2：被用户引用 → POSITION_IN_USE
        SysPosition position = position(1L, "engineer", "工程师");
        when(positionRepository.findById(1L)).thenReturn(Optional.of(position));
        when(userRepository.existsByPositionId(1L)).thenReturn(true);
        assertThatThrownBy(() -> useCase.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.POSITION_IN_USE.code()));
        verify(positionRepository, never()).delete(any(SysPosition.class));
    }

    @Test
    void deleteUnreferencedSucceeds() {
        // AC-2：无引用删除成功
        SysPosition position = position(1L, "engineer", "工程师");
        when(positionRepository.findById(1L)).thenReturn(Optional.of(position));
        when(userRepository.existsByPositionId(1L)).thenReturn(false);
        useCase.delete(1L);
        verify(positionRepository).delete(position);
    }

    @Test
    void updateDuplicatedCodeExcludingSelfRejected() {
        SysPosition position = position(1L, "engineer", "工程师");
        when(positionRepository.findById(1L)).thenReturn(Optional.of(position));
        when(positionRepository.existsByPositionCodeAndIdNot("dev", 1L)).thenReturn(true);
        assertThatThrownBy(() -> useCase.update(1L, new AdminPositionUpdate("dev", "研发", null, 0L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.POSITION_CODE_DUPLICATED.code()));
    }

    @Test
    void updateAppliesFieldsAndVersionMismatchRejected() {
        SysPosition position = position(1L, "engineer", "工程师");
        when(positionRepository.findById(1L)).thenReturn(Optional.of(position));
        when(positionRepository.save(any(SysPosition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SysPosition updated = useCase.update(1L, new AdminPositionUpdate("engineer", "高级工程师",
                EnableStatus.DISABLED, 0L));
        assertThat(updated.getPositionName()).isEqualTo("高级工程师");
        assertThat(updated.getStatus()).isEqualTo(EnableStatus.DISABLED);

        when(positionRepository.findById(1L)).thenReturn(Optional.of(position));
        assertThatThrownBy(() -> useCase.update(1L, new AdminPositionUpdate("engineer", "改名", null, 99L)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void getMissingRejected404() {
        when(positionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.get(99L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.POSITION_NOT_FOUND.code()));
    }

    private static SysPosition position(Long id, String code, String name) {
        SysPosition position = new SysPosition(code, name, EnableStatus.ENABLED);
        try {
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(position, id);
            var versionField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("version");
            versionField.setAccessible(true);
            versionField.set(position, 0L);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return position;
    }
}
