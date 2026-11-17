package com.acme.platform.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行异常日志保留期清理配置（REQ-20261117-061017 确认②）：exception-log-retention-cleanup
 * 调度 cron 与保留期阈值（硬删软删且 updated_at 超期的行）。
 */
@ConfigurationProperties(prefix = "acme.exception-log")
public class ExceptionLogRetentionProperties {

    /** 保留期天数（软删且 updated_at 超过该天数方被硬删）。 */
    private int retentionDays = 14;

    /** 清理任务 cron（缺省每日 03:40，与文件清理 03:00 错峰）。 */
    private String retentionCron = "0 40 3 * * *";

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
