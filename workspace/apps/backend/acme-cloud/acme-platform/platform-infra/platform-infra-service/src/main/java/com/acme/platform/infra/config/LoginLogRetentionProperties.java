package com.acme.platform.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录日志保留期清理任务配置（REQ-20261114-120105 login-log-retention-cleanup 调度口径）：
 * infra 仅持有调度 cron；保留期阈值（retention-days）归 platform-auth
 * （TL 裁决 C 方案：表访问与物理清理收敛 platform-auth，schema 独占口径）。
 */
@ConfigurationProperties(prefix = "acme.login-log")
public class LoginLogRetentionProperties {

    /** 清理任务 cron（缺省每日 03:30，与文件清理 03:00 错峰）。 */
    private String retentionCron = "0 30 3 * * *";

    public String getRetentionCron() {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron) {
        this.retentionCron = retentionCron;
    }
}
