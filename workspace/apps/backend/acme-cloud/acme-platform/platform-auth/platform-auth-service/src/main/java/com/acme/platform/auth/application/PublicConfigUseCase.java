package com.acme.platform.auth.application;

import com.acme.platform.auth.api.TenantPublicConfigView;
import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.system.api.TenantPublicInternalView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 公开站点配置用例（REQ-20261117-025200 确认记录⑤）：登录页页脚显隐的公开查询薄编排
 * （Feign 聚合经 SystemConfigGateway，服务身份主体 Q-D08，同 CaptchaPolicyUseCase 口径）；
 * 页脚非安全面，下游不可用 fail-open 返回缺省视图（WARN 记录，契约 §API：无行/未知租户 → 缺省）。
 */
@Service
public class PublicConfigUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublicConfigUseCase.class);

    private final SystemConfigGateway systemConfigGateway;

    public PublicConfigUseCase(SystemConfigGateway systemConfigGateway) {
        this.systemConfigGateway = systemConfigGateway;
    }

    /** 租户页脚公开视图（有行按行值，无行/未知租户缺省；下游不可用 fail-open 缺省返回）。 */
    public TenantPublicConfigView findPublicConfig(String tenantCode) {
        try {
            TenantPublicInternalView view = systemConfigGateway.findPublicView(tenantCode);
            return view == null ? TenantPublicConfigView.defaultValue()
                    : new TenantPublicConfigView(view.footerCopyright(), view.footerVisible());
        } catch (RuntimeException ex) {
            log.warn("公开站点配置查询失败，fail-open 返回缺省（页脚非安全面）：tenantCode={}", tenantCode, ex);
            return TenantPublicConfigView.defaultValue();
        }
    }
}
