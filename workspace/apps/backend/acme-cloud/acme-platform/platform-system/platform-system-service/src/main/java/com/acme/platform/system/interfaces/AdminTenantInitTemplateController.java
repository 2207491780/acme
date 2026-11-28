package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateCreate;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateUpdate;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateView;
import com.acme.platform.system.application.TenantInitTemplateUseCase;
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
 * 租户开通初始化模板接口（REQ-20261122-233426 确认④四端点，跨租户平台管理场景 12.4.2；
 * 无详情端点——list 读语义 + content 随分页行透传，通知公告/部门先例）：
 * 业务失败走 Result 信封（code≠0），HTTP 200/4xx 对齐框架翻译器现状。
 */
@RestController
@RequestMapping("/admin/v1/system/tenant-templates")
public class AdminTenantInitTemplateController {

    private final TenantInitTemplateUseCase tenantInitTemplateUseCase;

    public AdminTenantInitTemplateController(TenantInitTemplateUseCase tenantInitTemplateUseCase) {
        this.tenantInitTemplateUseCase = tenantInitTemplateUseCase;
    }

    /** 模板分页（keyword 对 code/name 模糊；排序 id asc）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.TENANT_TEMPLATE_LIST)
    public PageResult<AdminTenantInitTemplateView> list(@RequestParam(defaultValue = "1") Integer page,
                                                        @RequestParam(defaultValue = "20") Integer size,
                                                        @RequestParam(required = false) String keyword) {
        return tenantInitTemplateUseCase.page(new PageQuery(page, size), keyword);
    }

    @PostMapping
    @RequirePermission(SystemPermissions.TENANT_TEMPLATE_CREATE)
    @AuditLog(action = "system.tenantTemplate.create", targetType = "tenant-init-template",
            contentParams = {"request.code", "request.name"})
    public AdminTenantInitTemplateView create(@Valid @RequestBody AdminTenantInitTemplateCreate request) {
        return tenantInitTemplateUseCase.create(request);
    }

    @PutMapping("/{templateId}")
    @RequirePermission(SystemPermissions.TENANT_TEMPLATE_UPDATE)
    @AuditLog(action = "system.tenantTemplate.update", targetType = "tenant-init-template",
            targetId = "#templateId", beforeQuery = "@tenantInitTemplateUseCase.get(#templateId)",
            contentParams = {"templateId", "request.code"})
    public AdminTenantInitTemplateView update(@PathVariable Long templateId,
                                              @Valid @RequestBody AdminTenantInitTemplateUpdate request) {
        return tenantInitTemplateUseCase.update(templateId, request);
    }

    @DeleteMapping("/{templateId}")
    @RequirePermission(SystemPermissions.TENANT_TEMPLATE_DELETE)
    @AuditLog(action = "system.tenantTemplate.delete", targetType = "tenant-init-template",
            targetId = "#templateId", contentParams = {"templateId"})
    public void delete(@PathVariable Long templateId) {
        tenantInitTemplateUseCase.delete(templateId);
    }
}
