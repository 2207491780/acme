package com.acme.platform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录日志保留期配置（REQ-20261114-120105 清理链路；配置归属 platform-auth——表与物理清理
 * 收敛本服务，schema 独占口径）：retention-days 为软删标记判定窗口（按 created_at），
 * 缺省 30 天（TL 钉死默认 ≥2 天，tester 400d/1d 双档鲁棒设计兼容）。
 */
@ConfigurationProperties(prefix = "acme.login-log")
public class LoginLogRetentionProperties {

    /** 软删保留天数（缺省 30）。 */
    private int retentionDays = 30;

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
