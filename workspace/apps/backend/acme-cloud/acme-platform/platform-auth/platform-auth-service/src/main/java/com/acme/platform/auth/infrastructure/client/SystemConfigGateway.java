package com.acme.platform.auth.infrastructure.client;

import com.acme.common.context.UserContext;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.SystemConfigClient;
import com.acme.platform.system.api.TenantPublicInternalView;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 系统配置网关（REQ-20261117-011514 captcha-policy）：登录验证码策略查询数据源，
 * 以服务身份主体调用（Q-D08，同 TenantDirectoryGateway 口径）；
 * 下游错误透传（可观测不掩盖故障，fail-closed 判定由调用方承担）。
 */
@Component
public class SystemConfigGateway {

    private final SystemConfigClient systemConfigClient;

    public SystemConfigGateway(SystemConfigClient systemConfigClient) {
        this.systemConfigClient = systemConfigClient;
    }

    /** 租户验证码策略（tenantId 传租户代码；无配置行下游返回缺省关闭）。 */
    public CaptchaPolicyView findCaptchaPolicy(String tenantCode) {
        UserContext previous = UserContext.get();
        UserContext.bind(new UserContext(AuthProfileGateway.SERVICE_IDENTITY, Set.of()));
        try {
            CaptchaPolicyView policy = systemConfigClient.findCaptchaPolicy(tenantCode).payload();
            return policy == null ? new CaptchaPolicyView(false) : policy;
        } finally {
            UserContext.bind(previous);
        }
    }

    /**
     * 租户公开站点配置（REQ-20261117-025200 页脚两字段；tenantId 传租户代码，
     * 无配置行下游返回缺省 null/false，确认记录⑤）。
     */
    public TenantPublicInternalView findPublicView(String tenantCode) {
        UserContext previous = UserContext.get();
        UserContext.bind(new UserContext(AuthProfileGateway.SERVICE_IDENTITY, Set.of()));
        try {
            TenantPublicInternalView view = systemConfigClient.findPublicView(tenantCode).payload();
            return view == null ? new TenantPublicInternalView(null, false) : view;
        } finally {
            UserContext.bind(previous);
        }
    }
}
