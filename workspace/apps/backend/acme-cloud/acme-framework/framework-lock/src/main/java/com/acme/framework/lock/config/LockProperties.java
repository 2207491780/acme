package com.acme.framework.lock.config;

import com.acme.framework.lock.support.LockFailurePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-lock 配置属性（FR-022、Backend.md 8.1.3）：前缀 {@code acme.lock}，全字段默认值（架构设计 12）。
 * Redis 连接参数不在此声明，统一取 {@code spring.data.redis.*}（14.3.3 单一事实源）。
 */
@ConfigurationProperties("acme.lock")
public class LockProperties {

    /** 模块开关（FR-030，默认 true）。 */
    private boolean enabled = true;

    /** 默认失败策略（FR-012，默认 FAIL_FAST）。 */
    private LockFailurePolicy defaultPolicy = LockFailurePolicy.FAIL_FAST;

    /** WAIT 策略等待上限毫秒（默认 1000）。 */
    private long waitMillis = 1000;

    /** 看门狗租期秒（Redisson lockWatchdogTimeout，默认 30；FR-011）。 */
    private long watchdogSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LockFailurePolicy getDefaultPolicy() {
        return defaultPolicy;
    }

    public void setDefaultPolicy(LockFailurePolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    public long getWaitMillis() {
        return waitMillis;
    }

    public void setWaitMillis(long waitMillis) {
        this.waitMillis = waitMillis;
    }

    public long getWatchdogSeconds() {
        return watchdogSeconds;
    }

    public void setWatchdogSeconds(long watchdogSeconds) {
        this.watchdogSeconds = watchdogSeconds;
    }
}
