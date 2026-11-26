package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.UserFormOptionsView;
import com.acme.platform.system.application.DeptUseCase;
import com.acme.platform.system.application.PositionUseCase;
import com.acme.platform.system.application.RoleUseCase;
import com.acme.platform.system.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户表单目录接口（REQ-20261125-230807）：GET /admin/v1/system/user-form-options——
 * 用户管理页筛选 / 表单 / 列名称映射的目录一次拉全（deptTree 全量树含禁用态 + positions/roles 全量平铺）。
 * 权限挂 system:user:list 既有码（无新权限点种子）：非超管账号 403 静默降级收口——
 * 替代该页对 system:dept:list / system:position:list / 角色分页接口的依赖；只读端点，无审计面。
 */
@RestController
@RequestMapping("/admin/v1/system")
public class UserFormOptionsController {

    private final DeptUseCase deptUseCase;
    private final PositionUseCase positionUseCase;
    private final RoleUseCase roleUseCase;

    public UserFormOptionsController(DeptUseCase deptUseCase, PositionUseCase positionUseCase,
                                     RoleUseCase roleUseCase) {
        this.deptUseCase = deptUseCase;
        this.positionUseCase = positionUseCase;
        this.roleUseCase = roleUseCase;
    }

    /** 用户表单目录（deptTree=EntityViews.deptTree 全量；positions/roles 经用例仓储全查，规模受控）。 */
    @GetMapping("/user-form-options")
    @RequirePermission(SystemPermissions.USER_LIST)
    public UserFormOptionsView userFormOptions() {
        return new UserFormOptionsView(
                EntityViews.deptTree(deptUseCase.listAll()),
                positionUseCase.listAll().stream().map(EntityViews::positionView).toList(),
                roleUseCase.listAll().stream().map(EntityViews::roleView).toList());
    }
}
