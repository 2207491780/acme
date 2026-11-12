package com.acme.framework.i18n.support;

import com.acme.framework.i18n.config.I18nProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

import java.util.Locale;

/**
 * common-i18n {@link com.acme.common.i18n.MessageSource} 适配（FR-007、D-005 DP-012 定稿）：
 * 缺 key 默认返回 key 本身（RETURN_KEY，不抛、不阻断业务）；可配置 THROW 严格模式；
 * 消息缺失回退链：请求 Locale → 回退 Locale（Q-009）。
 */
public class MessageSourceAdapter implements com.acme.common.i18n.MessageSource {

    private final MessageSource delegate;
    private final Locale fallbackLocale;
    private final I18nProperties.MissingKeyPolicy missingKey;

    public MessageSourceAdapter(MessageSource delegate, Locale fallbackLocale,
                                I18nProperties.MissingKeyPolicy missingKey) {
        this.delegate = delegate;
        this.fallbackLocale = fallbackLocale;
        this.missingKey = missingKey == null ? I18nProperties.MissingKeyPolicy.RETURN_KEY : missingKey;
    }

    @Override
    public String getMessage(String key, Locale locale, Object... args) {
        Locale requested = locale == null ? fallbackLocale : locale;
        String message = delegate.getMessage(key, args, requested);
        if (!key.equals(message)) {
            return message;
        }
        // 请求 Locale 缺 key：回退 en 取值（Q-009 回退链）
        if (fallbackLocale != null && !fallbackLocale.equals(requested)) {
            String fallbackMessage = delegate.getMessage(key, args, fallbackLocale);
            if (!key.equals(fallbackMessage)) {
                return fallbackMessage;
            }
        }
        if (missingKey == I18nProperties.MissingKeyPolicy.THROW) {
            throw new NoSuchMessageException(key);
        }
        // RETURN_KEY：返回 key 本身（DP-012 默认口径）
        return key;
    }
}
