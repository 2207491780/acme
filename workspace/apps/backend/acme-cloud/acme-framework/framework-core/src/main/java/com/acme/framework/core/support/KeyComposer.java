package com.acme.framework.core.support;

import org.springframework.core.env.Environment;

import java.util.StringJoiner;

/**
 * 键构造规范（Modules.md 4.3 下沉共享；P4 内部零横向依赖）：统一「服务前缀 + 段拼接」口径，
 * 供 framework-cache（FR-008）、framework-lock（锁 key）、framework-job（FR-029 租户锁 key）、
 * framework-stream（幂等去重键）同源引用（Backend.md 12.3.2 服务前缀）。
 *
 * <p>纯逻辑、无 IO；服务前缀取 {@code spring.application.name}（构造期经 {@link Environment} 解析）。</p>
 */
public final class KeyComposer {

    private static final String DEFAULT_SERVICE = "application";

    private final String servicePrefix;

    public KeyComposer(Environment environment) {
        this(environment == null ? DEFAULT_SERVICE
                : environment.getProperty("spring.application.name", DEFAULT_SERVICE));
    }

    public KeyComposer(String servicePrefix) {
        String normalized = servicePrefix == null || servicePrefix.isBlank() ? DEFAULT_SERVICE : servicePrefix.trim();
        this.servicePrefix = normalized;
    }

    /** 服务前缀（Backend.md 12.3.2）。 */
    public String servicePrefix() {
        return servicePrefix;
    }

    /**
     * 按「服务:段:段…」拼接（空白段显式失败，防键漂移）。
     */
    public String compose(String... segments) {
        if (segments == null || segments.length == 0) {
            throw new IllegalArgumentException("键段不能为空");
        }
        StringJoiner joiner = new StringJoiner(":").add(servicePrefix);
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("键段不能为空");
            }
            joiner.add(segment.trim());
        }
        return joiner.toString();
    }
}
