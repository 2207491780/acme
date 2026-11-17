package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminRoleIds;
import com.acme.platform.system.api.dto.AdminStatusUpdate;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.api.dto.AdminUserUpdate;
import com.acme.platform.system.api.dto.AdminUserView;
import com.acme.platform.system.application.UserUseCase;
import com.acme.platform.system.domain.SysUser;
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
 * 用户管理接口（FR-006/007；路径含端别前缀——网关不 StripPrefix，架构设计 6.1 取舍 #8）。
 * REQ-20261114-120104：列表 deptId 入参（含子部门）+ 数据范围五档过滤（示范域）；
 * 创建 / 编辑接受 deptId / positionId。
 */
@RestController
@RequestMapping("/admin/v1/system/users")
public class UserController {

    private final UserUseCase userUseCase;

    public UserController(UserUseCase userUseCase) {
        this.userUseCase = userUseCase;
    }

    @GetMapping
    @RequirePermission(SystemPermissions.USER_LIST)
    public PageResult<AdminUserView> list(@RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "20") Integer size,
                                          @RequestParam(required = false) String username,
                                          @RequestParam(required = false) EnableStatus status,
                                          @RequestParam(required = false) Long deptId) {
        PageResult<SysUser> result = userUseCase.page(new PageQuery(page, size), username, status, deptId);
        List<AdminUserView> records = result.records().stream()
                .map(user -> EntityViews.userView(user, userUseCase.roleIdsOf(user.getId()))).toList();
        return new PageResult<>(records, result.total());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.USER_CREATE)
    @AuditLog(action = "system.user.create", targetType = "user",
            contentParams = {"request.username", "request.nickname"})
    public AdminUserView create(@Valid @RequestBody AdminUserCreate request) {
        SysUser user = userUseCase.create(request);
        return EntityViews.userView(user, userUseCase.roleIdsOf(user.getId()));
    }

    @GetMapping("/{userId}")
    @RequirePermission(SystemPermissions.USER_QUERY)
    public AdminUserView get(@PathVariable Long userId) {
        return EntityViews.userView(userUseCase.get(userId), userUseCase.roleIdsOf(userId));
    }

    @PutMapping("/{userId}")
    @RequirePermission(SystemPermissions.USER_UPDATE)
    @AuditLog(action = "system.user.update", targetType = "user", targetId = "#userId",
            beforeQuery = "@userUseCase.get(#userId)",
            contentParams = {"userId", "request.nickname", "request.email"})
    public AdminUserView update(@PathVariable Long userId, @Valid @RequestBody AdminUserUpdate request) {
        return EntityViews.userView(userUseCase.update(userId, request), userUseCase.roleIdsOf(userId));
    }

    @DeleteMapping("/{userId}")
    @RequirePermission(SystemPermissions.USER_DELETE)
    @AuditLog(action = "system.user.delete", targetType = "user", targetId = "#userId",
            contentParams = {"userId"})
    public void delete(@PathVariable Long userId) {
        userUseCase.delete(userId);
    }

    @PutMapping("/{userId}/status")
    @RequirePermission(SystemPermissions.USER_UPDATE)
    @AuditLog(action = "system.user.updateStatus", targetType = "user", targetId = "#userId",
            contentParams = {"userId", "request.status"})
    public void updateStatus(@PathVariable Long userId, @Valid @RequestBody AdminStatusUpdate request) {
        userUseCase.updateStatus(userId, request.status());
    }

    @PutMapping("/{userId}/roles")
    @RequirePermission(SystemPermissions.USER_ASSIGN_ROLE)
    @AuditLog(action = "system.user.assignRole", targetType = "user", targetId = "#userId",
            contentParams = {"userId", "request.roleIds"})
    public void bindRoles(@PathVariable Long userId, @Valid @RequestBody AdminRoleIds request) {
        userUseCase.bindRoles(userId, request.roleIds());
    }
}
