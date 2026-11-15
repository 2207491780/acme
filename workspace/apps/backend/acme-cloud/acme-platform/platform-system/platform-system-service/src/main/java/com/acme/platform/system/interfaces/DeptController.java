package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminDeptCreate;
import com.acme.platform.system.api.dto.AdminDeptUpdate;
import com.acme.platform.system.api.dto.AdminDeptView;
import com.acme.platform.system.application.DeptUseCase;
import com.acme.platform.system.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理接口（REQ-20261114-120104，权限 system:dept:*）：树形返回含禁用态 + 增改删（名称/父级/排序/状态）。
 */
@RestController
@RequestMapping("/admin/v1/system/depts")
public class DeptController {

    private final DeptUseCase deptUseCase;

    public DeptController(DeptUseCase deptUseCase) {
        this.deptUseCase = deptUseCase;
    }

    /** 部门树（含禁用态；子部门按 sort_order / id 稳定序）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.DEPT_LIST)
    public List<AdminDeptView> tree() {
        return EntityViews.deptTree(deptUseCase.listAll());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.DEPT_CREATE)
    public AdminDeptView create(@Valid @RequestBody AdminDeptCreate request) {
        return EntityViews.deptView(deptUseCase.create(request));
    }

    @GetMapping("/{deptId}")
    @RequirePermission(SystemPermissions.DEPT_LIST)
    public AdminDeptView get(@PathVariable Long deptId) {
        return EntityViews.deptView(deptUseCase.get(deptId));
    }

    @PutMapping("/{deptId}")
    @RequirePermission(SystemPermissions.DEPT_UPDATE)
    public AdminDeptView update(@PathVariable Long deptId, @Valid @RequestBody AdminDeptUpdate request) {
        return EntityViews.deptView(deptUseCase.update(deptId, request));
    }

    /** 删除部门（统一响应体 Result 契约口径：void 语义显式出信封，code="0"）。 */
    @DeleteMapping("/{deptId}")
    @RequirePermission(SystemPermissions.DEPT_DELETE)
    public Result<Void> delete(@PathVariable Long deptId) {
        deptUseCase.delete(deptId);
        return Result.ok(null);
    }
}
