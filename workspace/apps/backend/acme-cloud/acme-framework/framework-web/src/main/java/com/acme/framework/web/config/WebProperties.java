package com.acme.framework.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-web 配置属性（FR-026、Backend.md 8.1.3）：前缀 {@code acme.web}，全字段默认值。
 */
@ConfigurationProperties("acme.web")
public class WebProperties {

    /** 模块开关（FR-026，默认 true）。 */
    private boolean enabled = true;

    private final AccessLog accessLog = new AccessLog();

    private final ClientContext clientContext = new ClientContext();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AccessLog getAccessLog() {
        return accessLog;
    }

    public ClientContext getClientContext() {
        return clientContext;
    }

    /** 访问日志配置（FR-006）。 */
    public static class AccessLog {

        /** 访问日志开关（FR-006，默认 true）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** ClientContext 解析配置（FR-007，5.4）。 */
    public static class ClientContext {

        /** 非法端别声明处理策略（FR-007 缺省/拒绝策略，默认 IGNORE）。 */
        private ClientContextInvalidPolicy invalidPolicy = ClientContextInvalidPolicy.IGNORE;

        public ClientContextInvalidPolicy getInvalidPolicy() {
            return invalidPolicy;
        }

        public void setInvalidPolicy(ClientContextInvalidPolicy invalidPolicy) {
            this.invalidPolicy = invalidPolicy;
        }
    }
}
