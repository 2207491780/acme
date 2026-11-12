package com.acme.platform.auth.interfaces;

import com.acme.platform.auth.application.TenantDirectoryUseCase;
import com.acme.platform.system.api.TenantSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录辅助公开接口（login-tenant-select；路径含端别前缀 /admin/v1/auth/**）：
 * 租户下拉数据源为公开豁免端点（网关 + 服务侧 acme.security.public-paths 双侧一致最小集，FR-012），
 * 仅供登录页使用，返回启用租户摘要（代码 + 名称），不含任何敏感配置。
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class AuthTenantController {

    private final TenantDirectoryUseCase tenantDirectoryUseCase;

    public AuthTenantController(TenantDirectoryUseCase tenantDirectoryUseCase) {
        this.tenantDirectoryUseCase = tenantDirectoryUseCase;
    }

    /** 启用租户清单（公开端点；登录页可搜索下拉数据源，按租户代码排序）。 */
    @GetMapping("/tenants")
    public List<TenantSummary> tenants() {
        return tenantDirectoryUseCase.listEnabled();
    }
}
