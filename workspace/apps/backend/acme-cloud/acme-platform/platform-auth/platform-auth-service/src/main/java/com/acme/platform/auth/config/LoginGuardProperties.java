package com.acme.platform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录防爆破配置（架构设计 4.2 / 9.3；前缀 {@code acme.auth.login}，Q-002 裁定本期实现）：
 * Redis 计数 + 锁定，默认 5 次 / 15 分钟窗口 / 锁 15 分钟；Redis 不可用降级 fail-open（取舍 #7）。
 */
@ConfigurationProperties("acme.auth.login")
public class LoginGuardProperties {

    /** 失败锁定阈值（次，默认 5）。 */
    private int maxFailures = 5;

    /** 失败计数窗口（秒，默认 900=15 分钟）。 */
    private long windowSeconds = 900;

    /** 锁定时长（秒，默认 900=15 分钟）。 */
    private long lockSeconds = 900;

    public int getMaxFailures() {
        return maxFailures;
    }

    public void setMaxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public long getLockSeconds() {
        return lockSeconds;
    }

    public void setLockSeconds(long lockSeconds) {
        this.lockSeconds = lockSeconds;
    }
}
