package com.acme.common.i18n;

import java.util.Locale;

/**
 * MessageSource 抽象（FR-028；自有抽象，非 Spring 类型）。供 framework-i18n 装配与服务侧多语言消息解析使用。
 * 缺 key 口径（DP-012 定稿，p5-optional-modules D-005）：默认 {@code RETURN_KEY}——返回 key 本身，
 * 不抛出、不阻断业务；实现可提供可配置兜底，但默认行为须可判定。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public interface MessageSource {

    /**
     * 按消息键、占位参数、Locale 解析本地化消息（FR-028）；缺 key 返回 key 本身（DP-012）。
     */
    String getMessage(String key, Locale locale, Object... args);
}
