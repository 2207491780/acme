package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminTenantPackageCreate;
import com.acme.platform.system.api.dto.AdminTenantPackageDetailView;
import com.acme.platform.system.api.dto.AdminTenantPackageUpdate;
import com.acme.platform.system.api.dto.AdminTenantPackageView;
import com.acme.platform.system.application.TenantPackageUseCase;
import com.acme.framework.web.security.RequirePermission;
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
 * 租户套餐接口（REQ-20261121-022910 五端点，跨租户平台管理场景 12.4.2）：
 * 详情 GET /{id} 归 list 读语义（通知公告/部门先例）；业务失败走 Result 信封（code≠0），
 * HTTP 200/4xx 对齐框架翻译器现状。
 */
@RestController
@RequestMapping("/admin/v1/system/tenant-packages")
public class TenantPackageController {

    private final TenantPackageUseCase tenantPackageUseCase;

    public TenantPackageController(TenantPackageUseCase tenantPackageUseCase) {
        this.tenantPackageUseCase = tenantPackageUseCase;
    }

    /** 套餐分页（keyword 对 packageName 模糊；排序 id asc）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.TENANT_PACKAGE_LIST)
    public PageResult<AdminTenantPackageView> list(@RequestParam(defaultValue = "1") Integer page,
                                                   @RequestParam(defaultValue = "20") Integer size,
                                                   @RequestParam(required = false) String keyword) {
        return tenantPackageUseCase.page(new PageQuery(page, size), keyword);
    }

    @PostMapping
    @RequirePermission(SystemPermissions.TENANT_PACKAGE_CREATE)
    @AuditLog(action = "system.tenantPackage.create", targetType = "tenant-package",
            contentParams = {"request.packageName"})
    public AdminTenantPackageView create(@Valid @RequestBody AdminTenantPackageCreate request) {
        return tenantPackageUseCase.create(request);
    }

    @GetMapping("/{packageId}")
    @RequirePermission(SystemPermissions.TENANT_PACKAGE_LIST)
    public AdminTenantPackageDetailView get(@PathVariable Long packageId) {
        return tenantPackageUseCase.detail(packageId);
    }

    @PutMapping("/{packageId}")
    @RequirePermission(SystemPermissions.TENANT_PACKAGE_UPDATE)
    @AuditLog(action = "system.tenantPackage.update", targetType = "tenant-package", targetId = "#packageId",
            beforeQuery = "@tenantPackageUseCase.get(#packageId)",
            contentParams = {"packageId", "request.packageName"})
    public AdminTenantPackageView update(@PathVariable Long packageId,
                                         @Valid @RequestBody AdminTenantPackageUpdate request) {
        return tenantPackageUseCase.update(packageId, request);
    }

    @DeleteMapping("/{packageId}")
    @RequirePermission(SystemPermissions.TENANT_PACKAGE_DELETE)
    @AuditLog(action = "system.tenantPackage.delete", targetType = "tenant-package", targetId = "#packageId",
            contentParams = {"packageId"})
    public void delete(@PathVariable Long packageId) {
        tenantPackageUseCase.delete(packageId);
    }
}
