package com.acme.platform.system.interfaces;

import com.acme.platform.system.application.DeptUseCase;
import com.acme.platform.system.application.PositionUseCase;
import com.acme.platform.system.application.RoleUseCase;
import com.acme.platform.system.api.dto.UserFormOptionsView;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.domain.SysPosition;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.security.RequirePermission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户表单目录接口表面测试（REQ-20261125-230807 ②）：GET /admin/v1/system/user-form-options——
 * deptTree=EntityViews.deptTree 全量树、positions/roles 全量平铺；权限挂 system:user:list 既有码锚点。
 */
class UserFormOptionsSurfaceTest {

    @Test
    void userFormOptionsAssemblesFullCatalog() {
        DeptUseCase deptUseCase = mock(DeptUseCase.class);
        PositionUseCase positionUseCase = mock(PositionUseCase.class);
        RoleUseCase roleUseCase = mock(RoleUseCase.class);

        SysDept hq = deptWithId(1L, 0L, "总部");
        SysDept child = deptWithId(2L, 1L, "平台组");
        when(deptUseCase.listAll()).thenReturn(List.of(hq, child));
        SysPosition position = new SysPosition("engineer", "工程师", EnableStatus.ENABLED);
        when(positionUseCase.listAll()).thenReturn(List.of(position));
        SysRole role = new SysRole(0L, "ops", "运维", null, false);
        when(roleUseCase.listAll()).thenReturn(List.of(role));

        UserFormOptionsController controller =
                new UserFormOptionsController(deptUseCase, positionUseCase, roleUseCase);
        UserFormOptionsView view = controller.userFormOptions();

        assertThat(view.deptTree()).hasSize(1);
        assertThat(view.deptTree().get(0).deptName()).isEqualTo("总部");
        assertThat(view.deptTree().get(0).children()).extracting(com.acme.platform.system.api.dto.AdminDeptView::deptName)
                .containsExactly("平台组");
        assertThat(view.positions()).hasSize(1);
        assertThat(view.positions().get(0).positionCode()).isEqualTo("engineer");
        // roles 全量平铺（roleView：根 parentId 归一 null、children 恒空）
        assertThat(view.roles()).hasSize(1);
        assertThat(view.roles().get(0).roleCode()).isEqualTo("ops");
        assertThat(view.roles().get(0).parentId()).isNull();
        assertThat(view.roles().get(0).children()).isEmpty();
    }

    @Test
    void permissionStaysUserListOnly() throws Exception {
        // 权限面锚点：挂 system:user:list 既有码，无新权限点种子
        RequirePermission annotation = UserFormOptionsController.class
                .getMethod("userFormOptions").getAnnotation(RequirePermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("system:user:list");
    }

    private static SysDept deptWithId(long id, long parentId, String name) {
        SysDept dept = new SysDept(parentId, name, 0, EnableStatus.ENABLED);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(dept, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return dept;
    }
}
