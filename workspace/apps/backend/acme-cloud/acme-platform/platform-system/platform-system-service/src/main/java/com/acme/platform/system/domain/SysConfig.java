package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户级系统配置（REQ-20261116-230354：每租户一行，tenant_id 唯一；软删除）。
 * 无配置行租户按缺省生效（水印开 / 验证码关 / 闲置锁屏关 / 页脚不显示；
 * 验证码缺省口径 REQ-20261117-011514，页脚缺省口径 REQ-20261117-025200 确认记录⑤），
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

    /** 页脚版权文案（可空=未配置；REQ-20261117-025200 确认记录⑤）。 */
    @Column("footer_copyright")
    private String footerCopyright;

    /** 页脚是否显示（列 NOT NULL DEFAULT false；实体缺省 false 对齐列默认）。 */
    @Column("footer_visible")
    private Boolean footerVisible;

    protected SysConfig() {
    }

    public SysConfig(Boolean watermarkEnabled, Boolean captchaEnabled, Integer lockIdleTimeoutMs) {
        this(watermarkEnabled, captchaEnabled, lockIdleTimeoutMs, null, Boolean.FALSE);
    }

    public SysConfig(Boolean watermarkEnabled, Boolean captchaEnabled, Integer lockIdleTimeoutMs,
                     String footerCopyright, Boolean footerVisible) {
        this.watermarkEnabled = watermarkEnabled;
        this.captchaEnabled = captchaEnabled;
        this.lockIdleTimeoutMs = lockIdleTimeoutMs;
        this.footerCopyright = footerCopyright;
        this.footerVisible = footerVisible == null ? Boolean.FALSE : footerVisible;
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

    public String getFooterCopyright() {
        return footerCopyright;
    }

    public void setFooterCopyright(String footerCopyright) {
        this.footerCopyright = footerCopyright;
    }

    public Boolean getFooterVisible() {
        return footerVisible;
    }

    public void setFooterVisible(Boolean footerVisible) {
        this.footerVisible = footerVisible;
    }
}