package com.acme.framework.i18n.internal;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.i18n.support.MessageSourceAdapter;

import java.util.Locale;

/**
 * {@link PromptLocalizer} 实现（D-006、架构设计 4.1/7.3）：以错误码为消息键取本地化提示；
 * 缺 key（返回 key 本身）时返回 {@code null}，由 framework-web 回退 Error 枚举默认文案（10.5.1）。
 */
public class PromptLocalizerImpl implements PromptLocalizer {

    private final MessageSourceAdapter messageSource;

    public PromptLocalizerImpl(MessageSourceAdapter messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String localize(ErrorCode errorCode, Locale locale) {
        if (errorCode == null) {
            return null;
        }
        String key = errorCode.code();
        String message = messageSource.getMessage(key, locale);
        return key.equals(message) ? null : message;
    }
}
