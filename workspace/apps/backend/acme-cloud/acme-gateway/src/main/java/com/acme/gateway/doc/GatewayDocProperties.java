package com.acme.gateway.doc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聚合 API 文档配置（REQ-20261114-120106）：单一配置源开关 {@code acme.doc.enabled}
 * （对齐 framework-doc acme.doc.* 风格）。
 */
@ConfigurationProperties("acme.doc")
public class GatewayDocProperties {

    /** 聚合文档总开关（默认 true）：false 时 /v3/api-docs/** 经 DocGuardFilter 前置拒绝 404 DOC_DISABLED
     *  （请求不触达下游服务），/docs UI 端点随 springdoc.swagger-ui.enabled 同步关闭。 */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
