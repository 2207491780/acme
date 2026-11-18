package com.acme.framework.audit.internal;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 审计内容截取与脱敏（FR-010/AC-010、BR-013：不含敏感信息、堆栈、SQL、内部地址）。
 * 敏感键取对等文本掩码口径（架构设计 8.3：与 common-serialization DesensitizeStrategy 掩码语义对等，
 * 不引序列化依赖，避免 audit 依赖面膨胀）。
 */
public final class AuditContentTruncator {

    /** 敏感键模式（password/secret/token/key 等）。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            ".*((?i)password|passwd|secret|token|credential|api[-_]?key|access[-_]?key).*");

    /** 掩码占位。 */
    private static final String MASK = "***";

    private AuditContentTruncator() {
    }

    /**
     * 截取到上限并对敏感键掩码。
     *
     * @param content     内容摘要（{@code key=value} 形态）
     * @param maxLength   截取上限
     * @param maskEnabled 是否脱敏
     */
    public static String apply(String content, int maxLength, boolean maskEnabled) {
        if (content == null) {
            return null;
        }
        String masked = maskEnabled ? mask(content) : content;
        int limit = Math.max(maxLength, 0);
        return masked.length() <= limit ? masked : masked.substring(0, limit);
    }

    /** 对 {@code key=value} 段内敏感键值掩码（对等文本掩码，8.3）。 */
    static String mask(String content) {
        StringBuilder builder = new StringBuilder(content.length());
        for (String part : content.split(",\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0 && SENSITIVE.matcher(part.substring(0, eq)).matches()) {
                builder.append(part, 0, eq).append('=').append(MASK);
            } else {
                builder.append(part);
            }
            builder.append(", ");
        }
        return builder.length() > 2 ? builder.substring(0, builder.length() - 2) : content;
    }

    /** 键名匹配（供测试与切面复用）。 */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE.matcher(key.toLowerCase(Locale.ROOT)).matches();
    }
}
