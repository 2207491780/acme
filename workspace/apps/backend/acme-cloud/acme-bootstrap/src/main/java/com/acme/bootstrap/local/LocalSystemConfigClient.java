package com.acme.bootstrap.local;

import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemConfigClient;
import com.acme.platform.system.api.TenantPublicInternalView;
import com.acme.platform.system.application.SystemConfigUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 单体本地短路适配器（REQ-20261118-002130 确认②）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 system 域 {@link SystemConfigUseCase}，行为等价于 InternalSystemConfigController 的
 * /internal/v1/system-configs/** 端点。
 * 上下文口径：透明透传调用方 UserContext（auth 登录链已绑服务身份 platform-auth；
 * 跨租户查询由用例 @IgnoreTenant 声明）。
 */
@Component
@Primary
public class LocalSystemConfigClient implements SystemConfigClient {

    private final SystemConfigUseCase systemConfigUseCase;

    public LocalSystemConfigClient(SystemConfigUseCase systemConfigUseCase) {
        this.systemConfigUseCase = systemConfigUseCase;
    }

    /** 租户验证码策略（tenantId 传租户代码；无配置行返回缺省关闭）。 */
    @Override
    public ContractResult<CaptchaPolicyView> findCaptchaPolicy(String tenantId) {
        return LocalContractResults.ok(systemConfigUseCase.findCaptchaPolicyByTenant(tenantId));
    }

    /** 租户公开站点配置（页脚两字段；无配置行返回缺省 null/false）。 */
    @Override
    public ContractResult<TenantPublicInternalView> findPublicView(String tenantId) {
        return LocalContractResults.ok(systemConfigUseCase.findPublicViewByTenant(tenantId));
    }
}
