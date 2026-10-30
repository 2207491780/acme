package com.acme.framework.i18n.internal;

import com.acme.framework.i18n.config.I18nProperties;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.List;

/**
 * MessageSource 装配（FR-007、架构设计 7.3）：按 basenames + 默认 Locale + 回退链装配查找能力。
 * 默认 basename {@code i18n/acme-messages} 指向框架随附汇总资源（zh/en），业务可增配 basename。
 */
public final class MessageSourceAssembler {

    private MessageSourceAssembler() {
    }

    /** 装配 Spring MessageSource（缺 key 返回 key 本身，DP-012 RETURN_KEY）。 */
    public static ReloadableResourceBundleMessageSource assemble(I18nProperties properties) {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        List<String> basenames = properties.getBasenames() == null || properties.getBasenames().isEmpty()
                ? List.of("i18n/acme-messages") : properties.getBasenames();
        source.setBasenames(basenames.toArray(new String[0]));
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }
}
