package com.acme.framework.i18n.support;

import java.util.Locale;

/**
 * Locale 解析唯一入口（FR-006、BR-011：业务代码不得自造 Locale 解析）。
 * 覆盖头优先于协商头（架构设计 7.2）；头缺失/非法回退默认 Locale，不抛未处理异常。
 */
public class I18nLocaleResolver {

    private final Locale defaultLocale;
    private final Locale fallbackLocale;

    public I18nLocaleResolver(String defaultLocale, String fallbackLocale) {
        this.defaultLocale = parseOr(defaultLocale, Locale.SIMPLIFIED_CHINESE);
        this.fallbackLocale = parseOr(fallbackLocale, Locale.ENGLISH);
    }

    /**
     * 解析 Locale（FR-006）。
     *
     * @param overrideHeader 覆盖头值（如 X-Acme-Language）
     * @param acceptLanguage 协商头值（如 Accept-Language）
     */
    public Locale resolve(String overrideHeader, String acceptLanguage) {
        Locale parsed = parse(overrideHeader);
        if (parsed == null) {
            parsed = parse(acceptLanguage);
        }
        return parsed == null ? defaultLocale : parsed;
    }

    /** 默认 Locale（头缺失/非法回退值）。 */
    public Locale defaultLocale() {
        return defaultLocale;
    }

    /** 回退 Locale（消息缺失回退链末端，Q-009）。 */
    public Locale fallbackLocale() {
        return fallbackLocale;
    }

    /** 解析单个头值（非法/空白返回 null，不抛出）。 */
    static Locale parse(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        try {
            // Accept-Language 可能为「zh-CN,zh;q=0.9」形态：取第一段
            String first = headerValue.split(",")[0].trim();
            int semi = first.indexOf(';');
            if (semi >= 0) {
                first = first.substring(0, semi).trim();
            }
            if (first.isEmpty()) {
                return null;
            }
            Locale parsed = Locale.forLanguageTag(first.replace('_', '-'));
            return parsed.getLanguage().isEmpty() ? null : parsed;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Locale parseOr(String value, Locale fallback) {
        Locale parsed = parse(value);
        return parsed == null ? fallback : parsed;
    }
}
