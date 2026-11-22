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
import com.acme.platform.system.application.TenantQuotaUseCase;
import com.acme.platform.system.application.TenantUseCase;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
 * REQ-20261122-150802：视图富化解析后有效配额值（确认⑦ list 携 quotaStorageBytes；
 * usage/quota 端点归 {@link TenantQuotaController}，controller 拆分保持本类构造兼容面不变）。
 */
@RestController
@RequestMapping("/admin/v1/system/tenants")
public class TenantController {

    private final TenantUseCase tenantUseCase;
    private final TenantInitUseCase tenantInitUseCase;
    private final TenantQuotaUseCase tenantQuotaUseCase;

    @Autowired
    public TenantController(TenantUseCase tenantUseCase, TenantInitUseCase tenantInitUseCase,
                            TenantQuotaUseCase tenantQuotaUseCase) {
        this.tenantUseCase = tenantUseCase;
        this.tenantInitUseCase = tenantInitUseCase;
        this.tenantQuotaUseCase = tenantQuotaUseCase;
    }

    /** 兼容构造（配额富化缺席=视图 quotaStorageBytes 恒 null；既有直构调用面零改动）。 */
    public TenantController(TenantUseCase tenantUseCase, TenantInitUseCase tenantInitUseCase) {
        this(tenantUseCase, tenantInitUseCase, null);
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
                result.records().stream().map(this::enrichedTenantView).toList(), result.total());
    }

    @PostMapping
    @RequirePermission(SystemPermissions.TENANT_CREATE)
    @AuditLog(action = "system.tenant.create", targetType = "tenant",
            contentParams = {"request.tenantCode", "request.tenantName"})
    public AdminTenantView create(@Valid @RequestBody AdminTenantCreate request) {
        return enrichedTenantView(tenantUseCase.create(request));
    }

    /** 全量租户树（REQ-20261122-102954 确认⑨：平台侧层级视图，含 status/level/套餐；无新权限点）。 */
    @GetMapping("/tree")
    @RequirePermission(SystemPermissions.TENANT_LIST)
    public List<AdminTenantView> tree() {
        List<SysTenant> allTenants = tenantUseCase.listAll();
        return EntityViews.tenantTree(allTenants, quotaBytesByTenantId(allTenants));
    }

    @GetMapping("/{tenantId}")
    @RequirePermission(SystemPermissions.TENANT_QUERY)
    public AdminTenantView get(@PathVariable Long tenantId) {
        return enrichedTenantView(tenantUseCase.get(tenantId));
    }

    @PutMapping("/{tenantId}")
    @RequirePermission(SystemPermissions.TENANT_UPDATE)
    @AuditLog(action = "system.tenant.update", targetType = "tenant", targetId = "#tenantId",
            beforeQuery = "@tenantUseCase.get(#tenantId)",
            contentParams = {"tenantId", "request.tenantName"})
    public AdminTenantView update(@PathVariable Long tenantId, @Valid @RequestBody AdminTenantUpdate request) {
        return enrichedTenantView(tenantUseCase.update(tenantId, request));
    }

    @PutMapping("/{tenantId}/status")
    @RequirePermission(SystemPermissions.TENANT_UPDATE)
    @AuditLog(action = "system.tenant.updateStatus", targetType = "tenant", targetId = "#tenantId",
            contentParams = {"tenantId", "request.status"})
    public void updateStatus(@PathVariable Long tenantId, @Valid @RequestBody AdminStatusUpdate request) {
        tenantUseCase.updateStatus(tenantId, request.status());
    }

    /** 视图富化（REQ-20261122-150802 确认⑦）：quotaStorageBytes=三级覆盖链解析后有效值。 */
    private AdminTenantView enrichedTenantView(SysTenant tenant) {
        Long quotaBytes = tenantQuotaUseCase == null ? null : tenantQuotaUseCase.resolveQuotaBytes(tenant);
        return EntityViews.tenantView(tenant, quotaBytes);
    }

    /** 批量配额解析（免 N+1：覆盖行/套餐行各一次批量取回；兼容构造无用例时空 Map）。 */
    private Map<Long, Long> quotaBytesByTenantId(List<SysTenant> tenants) {
        return tenantQuotaUseCase == null ? Map.of() : tenantQuotaUseCase.resolveQuotaBytesByTenantId(tenants);
    }
}
