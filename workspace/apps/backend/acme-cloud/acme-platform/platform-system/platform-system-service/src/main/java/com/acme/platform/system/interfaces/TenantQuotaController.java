package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminTenantQuotaUpdate;
import com.acme.platform.system.api.dto.AdminTenantUsageView;
import com.acme.platform.system.application.TenantQuotaUseCase;
import com.acme.framework.web.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户配额/用量接口（REQ-20261122-150802 确认③④⑦⑧；与 TenantController 同基路径、子路径互异——
 * 拆分 controller 保持既有租户 CRUD 构造兼容面不变）：
 * usage=运营用量实时旁路聚合（system:tenant:list）；quota=租户覆盖配额写面（system:tenant:update，
 * 无新权限点，坑 16 不涉）。
 */
@RestController
@RequestMapping("/admin/v1/system/tenants")
public class TenantQuotaController {

    private final TenantQuotaUseCase tenantQuotaUseCase;

    public TenantQuotaController(TenantQuotaUseCase tenantQuotaUseCase) {
        this.tenantQuotaUseCase = tenantQuotaUseCase;
    }

    /** 租户用量（确认④⑧：storageBytes/sys_file 未删 sum 旁路聚合 + userCount + 解析后配额与来源层级）。 */
    @GetMapping("/{tenantId}/usage")
    @RequirePermission(SystemPermissions.TENANT_LIST)
    public AdminTenantUsageView usage(@PathVariable Long tenantId) {
        return tenantQuotaUseCase.usage(tenantId);
    }

    /** 租户配额覆盖（确认③⑦：非空=覆盖 0=不限；null=清除继承回落套餐/平台缺省；
     * 统一响应体 Result 契约口径：void 语义显式出信封，code="0"，REQ-20261125-230807）。 */
    @PutMapping("/{tenantId}/quota")
    @RequirePermission(SystemPermissions.TENANT_UPDATE)
    @AuditLog(action = "system.tenant.updateQuota", targetType = "tenant", targetId = "#tenantId",
            contentParams = {"tenantId", "request.quotaStorageBytes"})
    public Result<Void> setQuota(@PathVariable Long tenantId, @Valid @RequestBody AdminTenantQuotaUpdate request) {
        tenantQuotaUseCase.setTenantQuota(tenantId, request.quotaStorageBytes());
        return Result.ok(null);
    }
}
