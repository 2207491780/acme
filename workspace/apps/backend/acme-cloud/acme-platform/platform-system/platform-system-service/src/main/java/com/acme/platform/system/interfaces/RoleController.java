package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminMenuIds;
import com.acme.platform.system.api.dto.AdminMenuView;
import com.acme.platform.system.api.dto.AdminPermissionIds;
import com.acme.platform.system.api.dto.AdminPermissionView;
import com.acme.platform.system.api.dto.AdminRoleCreate;
import com.acme.platform.system.api.dto.AdminRoleUpdate;
import com.acme.platform.system.api.dto.AdminRoleView;
import com.acme.platform.system.api.dto.AdminStatusUpdate;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.application.PermissionUseCase;
import com.acme.platform.system.application.RoleUseCase;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与绑定接口（FR-006/007/008：接口级权限点绑定 + 菜单级权限绑定）。
 */
@RestController
@RequestMapping("/admin/v1/system/roles")
public class RoleController {

    private final RoleUseCase roleUseCase;
    private final PermissionUseCase permissionUseCase;
    private final MenuUseCase menuUseCase;

    public RoleController(RoleUseCase roleUseCase, PermissionUseCase permissionUseCase, MenuUseCase menuUseCase) {
        this.roleUseCase = roleUseCase;
        this.permissionUseCase = permissionUseCase;
        this.menuUseCase = menuUseCase;
    }

    @GetMapping
    @RequirePermission(SystemPermissions.ROLE_LIST)
    public PageResult<AdminRoleView> list(@RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "20") Integer size,
                                          @RequestParam(required = false) String keyword) {
        PageResult<SysRole> result = roleUseCase.page(new PageQuery(page, size), keyword);
        return new PageResult<>(
                result.records().stream().map(EntityViews::roleView).toList(), result.total());
    }

    /** 角色树（父子关系树状定义；子角色按 id 升序）。 */
    @GetMapping("/tree")
    @RequirePermission(SystemPermissions.ROLE_LIST)
    public List<AdminRoleView> tree() {
        return EntityViews.roleTree(roleUseCase.listAll());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.ROLE_CREATE)
    public AdminRoleView create(@Valid @RequestBody AdminRoleCreate request) {
        return EntityViews.roleView(roleUseCase.create(request));
    }

    @GetMapping("/{roleId}")
    @RequirePermission(SystemPermissions.ROLE_QUERY)
    public AdminRoleView get(@PathVariable Long roleId) {
        return EntityViews.roleView(roleUseCase.get(roleId));
    }

    @PutMapping("/{roleId}")
    @RequirePermission(SystemPermissions.ROLE_UPDATE)
    public AdminRoleView update(@PathVariable Long roleId, @Valid @RequestBody AdminRoleUpdate request) {
        return EntityViews.roleView(roleUseCase.update(roleId, request));
    }

    @DeleteMapping("/{roleId}")
    @RequirePermission(SystemPermissions.ROLE_DELETE)
    public void delete(@PathVariable Long roleId) {
        roleUseCase.delete(roleId);
    }

    @PutMapping("/{roleId}/status")
    @RequirePermission(SystemPermissions.ROLE_UPDATE)
    public void updateStatus(@PathVariable Long roleId, @Valid @RequestBody AdminStatusUpdate request) {
        roleUseCase.updateStatus(roleId, request.status());
    }

    @GetMapping("/{roleId}/permissions")
    @RequirePermission(SystemPermissions.ROLE_QUERY)
    public List<AdminPermissionView> permissions(@PathVariable Long roleId) {
        roleUseCase.get(roleId);
        return permissionUseCase.listByIds(roleUseCase.permissionIdsOf(roleId)).stream()
                .map(EntityViews::permissionView).toList();
    }

    @PutMapping("/{roleId}/permissions")
    @RequirePermission(SystemPermissions.ROLE_GRANT)
    public void bindPermissions(@PathVariable Long roleId, @Valid @RequestBody AdminPermissionIds request) {
        roleUseCase.bindPermissions(roleId, request.permissionIds());
    }

    @GetMapping("/{roleId}/menus")
    @RequirePermission(SystemPermissions.ROLE_QUERY)
    public List<AdminMenuView> menus(@PathVariable Long roleId) {
        roleUseCase.get(roleId);
        List<Long> menuIds = roleUseCase.menuIdsOf(roleId);
        return EntityViews.menuTree(menuUseCase.listAll(), new java.util.LinkedHashSet<>(menuIds));
    }

    @PutMapping("/{roleId}/menus")
    @RequirePermission(SystemPermissions.ROLE_GRANT)
    public void bindMenus(@PathVariable Long roleId, @Valid @RequestBody AdminMenuIds request) {
        roleUseCase.bindMenus(roleId, request.menuIds());
    }
}
