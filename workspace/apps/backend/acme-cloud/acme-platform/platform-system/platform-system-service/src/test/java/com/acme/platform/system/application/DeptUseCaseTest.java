package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminDeptCreate;
import com.acme.platform.system.api.dto.AdminDeptUpdate;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部门管理用例测试（REQ-20261114-120104，AC-1：树/增改删/删除守卫/防环/path 装配）。
 */
class DeptUseCaseTest {

    private final SysDeptRepository deptRepository = mock(SysDeptRepository.class);
    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysRoleDeptRepository roleDeptRepository = mock(SysRoleDeptRepository.class);

    private final DeptUseCase useCase = new DeptUseCase(deptRepository, userRepository, roleDeptRepository);

    @Test
    void createUnderRootFillsMaterializedPath() {
        // save 桩模拟真实底座行为：insert 后雪花 id 回填（path 装配依赖 id）
        when(deptRepository.save(any(SysDept.class))).thenAnswer(invocation -> {
            SysDept saved = invocation.getArgument(0);
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(saved, 100L);
            return saved;
        });
        SysDept created = useCase.create(new AdminDeptCreate(0L, "总部", 1, EnableStatus.ENABLED));
        // insert 后雪花 id 已定，path 依赖 id 二次回填（updatePath 装配 + 实体同步）
        assertThat(created.getId()).isNotNull();
        assertThat(created.getPath()).isEqualTo("/" + created.getId() + "/");
        verify(deptRepository).updatePath(any(), anyString());
    }

    @Test
    void createUnderMissingParentRejected() {
        when(deptRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.create(new AdminDeptCreate(99L, "孤儿", 0, null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DEPT_NOT_FOUND.code()));
    }

    @Test
    void deleteWithChildrenRejected() {
        // AC-1：有子部门 → DEPT_HAS_CHILDREN
        SysDept dept = dept(1L, 0L, "总部", "/1/");
        when(deptRepository.findById(1L)).thenReturn(Optional.of(dept));
        when(deptRepository.existsByParentId(1L)).thenReturn(true);
        assertThatThrownBy(() -> useCase.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DEPT_HAS_CHILDREN.code()));
        verify(deptRepository, never()).delete(any(SysDept.class));
    }

    @Test
    void deleteWithUsersRejected() {
        // AC-1：有用户归属 → DEPT_HAS_USERS
        SysDept dept = dept(1L, 0L, "总部", "/1/");
        when(deptRepository.findById(1L)).thenReturn(Optional.of(dept));
        when(deptRepository.existsByParentId(1L)).thenReturn(false);
        when(userRepository.existsByDeptId(1L)).thenReturn(true);
        assertThatThrownBy(() -> useCase.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DEPT_HAS_USERS.code()));
        verify(deptRepository, never()).delete(any(SysDept.class));
    }

    @Test
    void deleteLeafWithoutUsersSucceeds() {
        SysDept dept = dept(1L, 0L, "总部", "/1/");
        when(deptRepository.findById(1L)).thenReturn(Optional.of(dept));
        when(deptRepository.existsByParentId(1L)).thenReturn(false);
        when(userRepository.existsByDeptId(1L)).thenReturn(false);
        useCase.delete(1L);
        verify(deptRepository).delete(dept);
    }

    @Test
    void updateReparentUnderOwnDescendantRejected() {
        // 防环：总部(1) ← 研发部(2)；研发部重挂到自身/子孙 → DEPT_PARENT_CYCLE
        SysDept root = dept(1L, 0L, "总部", "/1/");
        SysDept child = dept(2L, 1L, "研发部", "/1/2/");
        when(deptRepository.findById(2L)).thenReturn(Optional.of(child));
        when(deptRepository.findById(1L)).thenReturn(Optional.of(root));
        when(deptRepository.save(any(SysDept.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // 自环：挂到自身
        assertThatThrownBy(() -> useCase.update(2L, new AdminDeptUpdate("研发部", 2L, 0, null, 0L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DEPT_PARENT_CYCLE.code()));
        // 子孙环：研发部(2) 挂到其子节点——链上无 2... 用 1 挂到 2（父在子孙链）反向构造：
        // 总部(1) 重挂到 研发部(2)：沿 2 → 1 命中自身 → 拒绝
        when(deptRepository.findById(1L)).thenReturn(Optional.of(root));
        assertThatThrownBy(() -> useCase.update(1L, new AdminDeptUpdate("总部", 2L, 0, null, 0L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(SystemError.DEPT_PARENT_CYCLE.code()));
    }

    @Test
    void updateReparentRewritesSubtreePaths() {
        // 总部(1, /1/) 重挂到 研发中心(9, /9/)：总部新 path=/9/1/，孙节点 /1/2/ → /9/1/2/
        SysDept root = dept(1L, 0L, "总部", "/1/");
        SysDept target = dept(9L, 0L, "研发中心", "/9/");
        SysDept grandchild = dept(3L, 1L, "后端组", "/1/2/3/");
        SysDept child = dept(2L, 1L, "研发部", "/1/2/");
        when(deptRepository.findById(1L)).thenReturn(Optional.of(root));
        when(deptRepository.findById(9L)).thenReturn(Optional.of(target));
        when(deptRepository.findSubtree("/1/")).thenReturn(List.of(root, child, grandchild));
        when(deptRepository.save(any(SysDept.class))).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.update(1L, new AdminDeptUpdate("总部", 9L, 0, null, 0L));

        assertThat(root.getPath()).isEqualTo("/9/1/");
        verify(deptRepository).updatePath(1L, "/9/1/");
        verify(deptRepository).updatePath(2L, "/9/1/2/");
        verify(deptRepository).updatePath(3L, "/9/1/2/3/");
        assertThat(root.getParentId()).isEqualTo(9L);
    }

    @Test
    void updateVersionMismatchRejected409() {
        SysDept dept = dept(1L, 0L, "总部", "/1/");
        when(deptRepository.findById(1L)).thenReturn(Optional.of(dept));
        assertThatThrownBy(() -> useCase.update(1L, new AdminDeptUpdate("总部", null, 0, null, 99L)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void subtreeIdsCoversSelfAndDescendants() {
        SysDept dept = dept(1L, 0L, "总部", "/1/");
        when(deptRepository.findById(1L)).thenReturn(Optional.of(dept));
        when(deptRepository.findSubtree("/1/")).thenReturn(List.of(dept, dept(2L, 1L, "研发部", "/1/2/")));
        assertThat(useCase.subtreeIds(1L)).containsExactly(1L, 2L);
    }

    private static SysDept dept(Long id, Long parentId, String name, String path) {
        SysDept dept = new SysDept(parentId, name, 0, EnableStatus.ENABLED);
        try {
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(dept, id);
            var versionField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("version");
            versionField.setAccessible(true);
            versionField.set(dept, 0L);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        dept.setPath(path);
        return dept;
    }
}
