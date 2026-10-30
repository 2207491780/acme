package com.acme.framework.trace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-trace 配置属性（FR-006、BR-008、Backend.md 8.1.3）：前缀 {@code acme.trace}，全字段默认值（NFR-005）。
 * 配置面刻意最小：pattern 由 {@code logging.pattern.*} 原生键承载，头名/MDC key 无配置（架构设计 3.7）。
 */
@ConfigurationProperties("acme.trace")
public class TraceProperties {

    /** 模块开关（FR-006；Q-004 默认 true）。 */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
