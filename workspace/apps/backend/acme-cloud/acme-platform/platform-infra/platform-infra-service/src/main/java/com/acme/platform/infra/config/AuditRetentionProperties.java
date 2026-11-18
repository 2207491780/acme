package com.acme.platform.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计事件保留期清理配置（REQ-20261117-061017 确认②）：audit-retention-cleanup 调度 cron 与保留期
 * 阈值（硬删软删且 updated_at 超期的行）。与 framework-audit AuditProperties（acme.audit.enabled 等
 * 埋点配置）同前缀不同字段，互不影响。
 */
@ConfigurationProperties(prefix = "acme.audit")
public class AuditRetentionProperties {

    /** 保留期天数（软删且 updated_at 超过该天数方被硬删）。 */
    private int retentionDays = 30;

    /** 清理任务 cron（缺省每日 04:40，与异常日志清理 03:40 错峰）。 */
    private String retentionCron = "0 40 4 * * *";

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getRetentionCron() {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron) {
        this.retentionCron = retentionCron;
    }
}
