package com.acme.platform.auth.interfaces;

import com.acme.common.core.BizException;
import com.acme.common.core.Result;
import com.acme.framework.web.support.WebError;
import com.acme.platform.auth.application.CaptchaPolicyUseCase;
import com.acme.platform.auth.application.TenantDirectoryUseCase;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.TenantSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录辅助公开接口（login-tenant-select / REQ-20261117-011514 captcha-policy；
 * 路径含端别前缀 /admin/v1/auth/**）：
 * 租户下拉与验证码策略查询均为公开豁免端点（网关 + 服务侧 acme.security.public-paths
 * 双侧一致最小集，FR-012），仅供登录页使用——租户清单返回启用租户摘要（代码 + 名称），
 * 验证码策略仅返回 boolean 开关生效值，均不含任何敏感配置。
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class AuthTenantController {

    private final TenantDirectoryUseCase tenantDirectoryUseCase;
    private final CaptchaPolicyUseCase captchaPolicyUseCase;

    public AuthTenantController(TenantDirectoryUseCase tenantDirectoryUseCase,
                                CaptchaPolicyUseCase captchaPolicyUseCase) {
        this.tenantDirectoryUseCase = tenantDirectoryUseCase;
        this.captchaPolicyUseCase = captchaPolicyUseCase;
    }

    /** 启用租户清单（公开端点；登录页可搜索下拉数据源，按租户代码排序）。 */
    @GetMapping("/tenants")
    public List<TenantSummary> tenants() {
        return tenantDirectoryUseCase.listEnabled();
    }

    /**
     * 租户验证码策略（公开端点，REQ-20261117-011514；登录页按租户显隐验证码输入）：
     * tenantCode 空白为参数错误（400）；下游不可用 fail-closed 返回开启（WARN 记录，AC-6）。
     */
    @GetMapping("/captcha-policy")
    public Result<CaptchaPolicyView> captchaPolicy(@RequestParam("tenantCode") String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(WebError.VALIDATION_FAILED);
        }
        return Result.ok(captchaPolicyUseCase.findCaptchaPolicy(tenantCode.trim()));
    }
}
