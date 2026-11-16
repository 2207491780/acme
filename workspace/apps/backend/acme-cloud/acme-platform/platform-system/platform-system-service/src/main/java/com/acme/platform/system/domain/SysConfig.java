package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户级系统配置（REQ-20261116-230354：每租户一行，tenant_id 唯一；软删除）。
 * 无配置行租户按缺省生效（水印开 / 验证码关 / 闲置锁屏关；验证码缺省口径 REQ-20261117-011514），
 * 缺省视图在接口层组装。
 */
@Table("sys_config")
public class SysConfig extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("watermark_enabled")
    private Boolean watermarkEnabled;

    @Column("captcha_enabled")
    private Boolean captchaEnabled;

    @Column("lock_idle_timeout_ms")
    private Integer lockIdleTimeoutMs;

    protected SysConfig() {
    }

    public SysConfig(Boolean watermarkEnabled, Boolean captchaEnabled, Integer lockIdleTimeoutMs) {
        this.watermarkEnabled = watermarkEnabled;
        this.captchaEnabled = captchaEnabled;
        this.lockIdleTimeoutMs = lockIdleTimeoutMs;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Boolean getWatermarkEnabled() {
        return watermarkEnabled;
    }

    public void setWatermarkEnabled(Boolean watermarkEnabled) {
        this.watermarkEnabled = watermarkEnabled;
    }

    public Boolean getCaptchaEnabled() {
        return captchaEnabled;
    }

    public void setCaptchaEnabled(Boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
    }

    public Integer getLockIdleTimeoutMs() {
        return lockIdleTimeoutMs;
    }

    public void setLockIdleTimeoutMs(Integer lockIdleTimeoutMs) {
        this.lockIdleTimeoutMs = lockIdleTimeoutMs;
    }
}