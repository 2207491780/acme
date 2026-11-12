package com.acme.framework.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 操作审计配置（FR-008~010、8.1.3 全字段默认值；架构设计 15 章）。
 */
@ConfigurationProperties(prefix = "acme.audit")
public class AuditProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 内容截取上限（FR-010）。 */
    private int contentMaxLength = 512;

    /** 敏感字段脱敏（FR-010、BR-013）。 */
    private boolean maskSensitive = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getContentMaxLength() {
        return contentMaxLength;
    }

    public void setContentMaxLength(int contentMaxLength) {
        this.contentMaxLength = contentMaxLength;
    }

    public boolean isMaskSensitive() {
        return maskSensitive;
    }

    public void setMaskSensitive(boolean maskSensitive) {
        this.maskSensitive = maskSensitive;
    }
}
