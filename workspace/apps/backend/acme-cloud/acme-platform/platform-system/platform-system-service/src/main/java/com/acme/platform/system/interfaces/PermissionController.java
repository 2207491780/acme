package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminPermissionCreate;
import com.acme.platform.system.api.dto.AdminPermissionUpdate;
import com.acme.platform.system.api.dto.AdminPermissionView;
import com.acme.platform.system.application.PermissionUseCase;
import com.acme.platform.system.domain.SysPermission;
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

/**
 * 权限点管理接口（FR-008 接口级权限点目录；平台级目录全租户可见）。
 */
@RestController
@RequestMapping("/admin/v1/system/permissions")
public class PermissionController {

    private final PermissionUseCase permissionUseCase;

    public PermissionController(PermissionUseCase permissionUseCase) {
        this.permissionUseCase = permissionUseCase;
    }

    @GetMapping
    @RequirePermission(SystemPermissions.PERMISSION_LIST)
    public PageResult<AdminPermissionView> list(@RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "20") Integer size,
                                                @RequestParam(required = false) String keyword) {
        PageResult<SysPermission> result = permissionUseCase.page(new PageQuery(page, size), keyword);
        return new PageResult<>(
                result.records().stream().map(EntityViews::permissionView).toList(), result.total());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.PERMISSION_CREATE)
    public AdminPermissionView create(@Valid @RequestBody AdminPermissionCreate request) {
        return EntityViews.permissionView(permissionUseCase.create(request));
    }

    @GetMapping("/{permissionId}")
    @RequirePermission(SystemPermissions.PERMISSION_QUERY)
    public AdminPermissionView get(@PathVariable Long permissionId) {
        return EntityViews.permissionView(permissionUseCase.get(permissionId));
    }

    @PutMapping("/{permissionId}")
    @RequirePermission(SystemPermissions.PERMISSION_UPDATE)
    public AdminPermissionView update(@PathVariable Long permissionId,
                                      @Valid @RequestBody AdminPermissionUpdate request) {
        return EntityViews.permissionView(permissionUseCase.update(permissionId, request));
    }

    @DeleteMapping("/{permissionId}")
    @RequirePermission(SystemPermissions.PERMISSION_DELETE)
    public void delete(@PathVariable Long permissionId) {
        permissionUseCase.delete(permissionId);
    }
}
