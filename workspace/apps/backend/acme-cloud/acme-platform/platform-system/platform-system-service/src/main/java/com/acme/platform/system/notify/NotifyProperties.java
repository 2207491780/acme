package com.acme.platform.system.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 通知推送配置（REQ-20261114-120102）：前缀 {@code acme.notify.ws}（空闲回收键=确认记录⑧钉死
 * {@code acme.notify.ws.idle-timeout}，Duration 缺省 30s），全字段默认值。
 * WS 握手用户令牌验签键=网关同键单源 {@code acme.gateway.user-token.*}（确认记录⑬，
 * 见 {@link NotifyUserTokenProperties}），与内部令牌密钥域（acme.security.token）隔离（11.6.2）。
 */
@ConfigurationProperties("acme.notify.ws")
public class NotifyProperties {

    /** 心跳空闲超时（确认记录⑧钉死键 {@code acme.notify.ws.idle-timeout}：静默超时断开并回收会话，缺省 30s）。 */
    private Duration idleTimeout = Duration.ofSeconds(30);

    /** 空闲回收扫描间隔（低于超时周期保证回收及时性，缺省 2s）。 */
    private Duration sweepInterval = Duration.ofSeconds(2);

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }
}
