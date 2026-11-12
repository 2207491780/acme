package com.acme.framework.jackson.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-jackson 配置属性（FR-010、BR-008、Backend.md 8.1.3）：前缀 {@code acme.jackson}，全字段默认值（NFR-005）。
 */
@ConfigurationProperties("acme.jackson")
public class JacksonProperties {

    /** 模块开关（FR-010；Q-004 默认 true）。 */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
