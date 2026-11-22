package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminStatusUpdate;
import com.acme.platform.system.api.dto.AdminTenantCreate;
import com.acme.platform.system.api.dto.AdminTenantInitRequest;
import com.acme.platform.system.api.dto.AdminTenantInitView;
import com.acme.platform.system.api.dto.AdminTenantUpdate;
import com.acme.platform.system.api.dto.AdminTenantView;
import com.acme.platform.system.application.TenantInitUseCase;
import com.acme.platform.system.application.TenantUseCase;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理接口（FR-006 Q-04；跨租户平台管理场景 12.4.2；无删除能力——停用代替）。
 * 开通初始化（tenant-management-complete）：建租户 + 默认授权 + 初始管理员一个事务完成；
 * 管理员初始密码开通后经凭据端点设置（凭据归 platform-auth，见 AdminTenantInitRequest 注）。
 */
@RestController
@RequestMapping("/admin/v1/system/tenants")
public class TenantController {

    private final TenantUseCase tenantUseCase;
    private final TenantInitUseCase tenantInitUseCase;

    public TenantController(TenantUseCase tenantUseCase, TenantInitUseCase tenantInitUseCase) {
        this.tenantUseCase = tenantUseCase;
        this.tenantInitUseCase = tenantInitUseCase;
    }

    /** 开通初始化（建租户 + 租户管理员角色默认授权 + 初始管理员；开通语义复用 TENANT_CREATE）。 */
    @PostMapping("/init")
    @RequirePermission(SystemPermissions.TENANT_CREATE)
    @AuditLog(action = "system.tenant.init", targetType = "tenant",
            contentParams = {"request.tenantCode", "request.adminUsername"})
    public AdminTenantInitView initialize(@Valid @RequestBody AdminTenantInitRequest request) {
        return tenantInitUseCase.initialize(request);
    }

    @GetMapping
    @RequirePermission(SystemPermissions.TENANT_LIST)
    public PageResult<AdminTenantView> list(@RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "20") Integer size,
                                            @RequestParam(required = false) String keyword) {
        PageResult<SysTenant> result = tenantUseCase.page(new PageQuery(page, size), keyword);
        return new PageResult<>(
                result.records().stream().map(EntityViews::tenantView).toList(), result.total());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.TENANT_CREATE)
    @AuditLog(action = "system.tenant.create", targetType = "tenant",
            contentParams = {"request.tenantCode", "request.tenantName"})
    public AdminTenantView create(@Valid @RequestBody AdminTenantCreate request) {
        return EntityViews.tenantView(tenantUseCase.create(request));
    }

    /** 全量租户树（REQ-20261122-102954 确认⑨：平台侧层级视图，含 status/level/套餐；无新权限点）。 */
    @GetMapping("/tree")
    @RequirePermission(SystemPermissions.TENANT_LIST)
    public List<AdminTenantView> tree() {
        return EntityViews.tenantTree(tenantUseCase.listAll());
    }

    @GetMapping("/{tenantId}")
    @RequirePermission(SystemPermissions.TENANT_QUERY)
    public AdminTenantView get(@PathVariable Long tenantId) {
        return EntityViews.tenantView(tenantUseCase.get(tenantId));
    }

    @PutMapping("/{tenantId}")
    @RequirePermission(SystemPermissions.TENANT_UPDATE)
    @AuditLog(action = "system.tenant.update", targetType = "tenant", targetId = "#tenantId",
            beforeQuery = "@tenantUseCase.get(#tenantId)",
            contentParams = {"tenantId", "request.tenantName"})
    public AdminTenantView update(@PathVariable Long tenantId, @Valid @RequestBody AdminTenantUpdate request) {
        return EntityViews.tenantView(tenantUseCase.update(tenantId, request));
    }

    @PutMapping("/{tenantId}/status")
    @RequirePermission(SystemPermissions.TENANT_UPDATE)
    @AuditLog(action = "system.tenant.updateStatus", targetType = "tenant", targetId = "#tenantId",
            contentParams = {"tenantId", "request.status"})
    public void updateStatus(@PathVariable Long tenantId, @Valid @RequestBody AdminStatusUpdate request) {
        tenantUseCase.updateStatus(tenantId, request.status());
    }
}
