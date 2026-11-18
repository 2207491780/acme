package com.acme.platform.auth.interfaces;

import com.acme.common.core.BizException;
import com.acme.common.core.Result;
import com.acme.framework.audit.support.AuditLog;
import com.acme.framework.web.support.WebError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.api.TenantPublicConfigView;
import com.acme.platform.auth.application.CaptchaPolicyUseCase;
import com.acme.platform.auth.application.PublicConfigUseCase;
import com.acme.platform.auth.application.TenantDirectoryUseCase;
import com.acme.platform.auth.application.TenantSwitchUseCase;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.TenantSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录辅助接口（login-tenant-select / REQ-20261117-011514 captcha-policy /
 * REQ-20261117-025200 页脚版权与超管跨租户切换；路径含端别前缀 /admin/v1/auth/**）：
 * 租户下拉 / 验证码策略 / 公开站点配置为公开豁免端点（网关 + 服务侧 acme.security.public-paths
 * 双侧一致最小集，FR-012），仅供登录页使用——租户清单返回启用租户摘要（代码 + 名称），
 * 验证码策略仅返回 boolean 开关生效值，公开站点配置仅暴露页脚两字段，均不含任何敏感配置；
 * 超管跨租户切换为认证端点（不进公开豁免，前置经 system 内部鉴权面实查内置超管角色）。
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class AuthTenantController {

    private final TenantDirectoryUseCase tenantDirectoryUseCase;
    private final CaptchaPolicyUseCase captchaPolicyUseCase;
    private final PublicConfigUseCase publicConfigUseCase;
    private final TenantSwitchUseCase tenantSwitchUseCase;

    public AuthTenantController(TenantDirectoryUseCase tenantDirectoryUseCase,
                                CaptchaPolicyUseCase captchaPolicyUseCase,
                                PublicConfigUseCase publicConfigUseCase,
                                TenantSwitchUseCase tenantSwitchUseCase) {
        this.tenantDirectoryUseCase = tenantDirectoryUseCase;
        this.captchaPolicyUseCase = captchaPolicyUseCase;
        this.publicConfigUseCase = publicConfigUseCase;
        this.tenantSwitchUseCase = tenantSwitchUseCase;
    }

    /** 启用租户清单（公开端点；登录页可搜索下拉数据源，按租户代码排序，已过期租户不含）。 */
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

    /**
     * 租户公开站点配置（公开端点，REQ-20261117-025200 确认记录⑤；登录页页脚显隐数据源）：
     * 仅暴露页脚两字段（有行按行值，无行/未知租户缺省 null/false）；
     * tenantCode 空白为参数错误（400）；下游不可用 fail-open 返回缺省（页脚非安全面，WARN 记录）。
     */
    @GetMapping("/public-config")
    public Result<TenantPublicConfigView> publicConfig(@RequestParam("tenantCode") String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(WebError.VALIDATION_FAILED);
        }
        return Result.ok(publicConfigUseCase.findPublicConfig(tenantCode.trim()));
    }

    /**
     * 超管跨租户切换（认证端点，REQ-20261117-025200 确认记录④）：前置经 system 内部鉴权面
     * 实查内置超管角色（非超管 403 TENANT_SWITCH_FORBIDDEN）；目标租户必须 ENABLED 且未过期
     * （否则 TENANT_SWITCH_FAILED）；签发即互踢旧会话（Q-003 单会话语义延续）。
     * 写动作纳入审计（REQ-20261117-061017 确认③）：contentParams 仅取目标租户代码。
     */
    @PostMapping("/tenant-switch")
    @AuditLog(action = "auth.tenant.switch", targetType = "tenant", contentParams = {"request.targetTenantCode"})
    public Result<AdminTokenIssued> tenantSwitch(@Valid @RequestBody TenantSwitchRequest request,
                                                 HttpServletRequest servletRequest) {
        return Result.ok(tenantSwitchUseCase.switchTenant(request.targetTenantCode().trim(),
                ClientRequests.resolveIp(servletRequest), ClientRequests.resolveUserAgent(servletRequest)));
    }
}
