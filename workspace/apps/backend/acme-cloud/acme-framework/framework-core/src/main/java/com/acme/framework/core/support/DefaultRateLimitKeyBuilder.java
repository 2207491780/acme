package com.acme.framework.core.support;

import com.acme.common.context.Channel;

/**
 * 限流 key 缺省构造（Modules.md 3.1.3）：B「服务前缀+端别B+账号+接口」、C「服务前缀+端别C+设备+用户+接口」；
 * 兜底键「服务前缀+端别标识FB+IP+接口」；接口段经 {@link #normalizeApi} 归一（资源 ID 段收敛，防高基数）。
 *
 * <p>p5-optional-modules D-001 自 framework-gateway 迁入，键格式保持既有兼容；
 * 服务侧注解场景 api 段为业务声明的资源标识（非路径形态，原样入键）。</p>
 */
public class DefaultRateLimitKeyBuilder implements RateLimitKeyBuilder {

    @Override
    public String build(RateLimitKeyInput input) {
        String prefix = input.servicePrefix() == null ? "" : input.servicePrefix();
        String api = normalizeApi(input.api());
        if (input.channel() == Channel.B) {
            return String.join(":", prefix, "B", safe(input.account()), api);
        }
        return String.join(":", prefix, "C", safe(input.deviceId()), safe(input.userId()), api);
    }

    @Override
    public String buildFallback(RateLimitFallbackInput input) {
        String prefix = input.servicePrefix() == null ? "" : input.servicePrefix();
        return String.join(":", prefix, "FB", safe(input.clientIp()), normalizeApi(input.api()));
    }

    /**
     * 接口归一：纯数字段/UUID/长 hex 段收敛为 {id}（3.1.3「接口」维度=路由模板语义）。
     * 非路径形态（服务侧注解的资源标识，不以 {@code /} 开头）原样入键（架构设计 11.3）。
     */
    static String normalizeApi(String path) {
        if (path == null || path.isBlank()) {
            return "-";
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) {
            return trimmed;
        }
        String[] segments = trimmed.split("/");
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            builder.append('/').append(isIdSegment(segment) ? "{id}" : segment);
        }
        return builder.isEmpty() ? "/" : builder.toString();
    }

    private static boolean isIdSegment(String segment) {
        if (segment.chars().allMatch(Character::isDigit)) {
            return true;
        }
        // UUID 形态
        if (segment.length() == 36 && segment.charAt(8) == '-' && segment.charAt(13) == '-'
                && segment.charAt(18) == '-' && segment.charAt(23) == '-') {
            return true;
        }
        // 长 hex（雪花/哈希标识）：≥16 位且全 hex
        return segment.length() >= 16
                && segment.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
