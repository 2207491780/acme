package com.acme.framework.core.support;

import org.springframework.core.env.Environment;

import java.util.StringJoiner;

/**
 * 键构造规范（Modules.md 4.3 下沉共享；P4 内部零横向依赖）：统一「[env:]服务前缀 + 段拼接」口径，
 * 供 framework-cache（FR-008）、framework-lock（锁 key）、framework-job（FR-029 租户锁 key）、
 * framework-stream（幂等去重键）同源引用（Backend.md 12.3.2 服务前缀）。
 *
 * <p>纯逻辑、无 IO；服务前缀取 {@code spring.application.name}（构造期经 {@link Environment} 解析）。
 * 可选环境段取 {@code acme.env-id}（系统环境变量 ACME_ENV_ID 松散绑定）：单套中间件承载多环境时置
 * 非空，使所有键与 Pub/Sub 通道前置环境段（prod/dev/test 互不可见，2026-11-18-02 决策——DB index
 * 管键、环境段管 Pub/Sub，两者互补缺一不可）；缺省空 = 输出与历史格式逐字符一致。</p>
 */
public final class KeyComposer {

    private static final String DEFAULT_SERVICE = "application";

    private final String servicePrefix;
    private final String envSegment;

    public KeyComposer(Environment environment) {
        this(environment == null ? DEFAULT_SERVICE
                : environment.getProperty("spring.application.name", DEFAULT_SERVICE),
            environment == null ? "" : environment.getProperty("acme.env-id", ""));
    }

    public KeyComposer(String servicePrefix) {
        this(servicePrefix, "");
    }

    /** 全参构造（显式装配/测试用）；envId 空白视为未启用环境隔离（键格式与历史一致）。 */
    public KeyComposer(String servicePrefix, String envId) {
        String normalized = servicePrefix == null || servicePrefix.isBlank() ? DEFAULT_SERVICE : servicePrefix.trim();
        String env = envId == null ? "" : envId.trim();
        if (env.indexOf(':') >= 0) {
            throw new IllegalArgumentException("acme.env-id 不能包含键段分隔符冒号: " + env);
        }
        this.servicePrefix = normalized;
        this.envSegment = env;
    }

    /** 服务前缀（Backend.md 12.3.2）。 */
    public String servicePrefix() {
        return servicePrefix;
    }

    /** 环境段（acme.env-id；空=未启用）。 */
    public String envSegment() {
        return envSegment;
    }

    /**
     * 按「[env:]服务:段:段…」拼接（空白段显式失败，防键漂移）。
     */
    public String compose(String... segments) {
        if (segments == null || segments.length == 0) {
            throw new IllegalArgumentException("键段不能为空");
        }
        StringJoiner joiner = new StringJoiner(":");
        if (!envSegment.isEmpty()) {
            joiner.add(envSegment);
        }
        joiner.add(servicePrefix);
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("键段不能为空");
            }
            joiner.add(segment.trim());
        }
        return joiner.toString();
    }
}
