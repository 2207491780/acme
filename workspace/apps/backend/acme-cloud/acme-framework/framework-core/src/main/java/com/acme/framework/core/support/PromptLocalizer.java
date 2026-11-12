package com.acme.framework.core.support;

import com.acme.common.core.ErrorCode;

import java.util.Locale;

/**
 * 面向调用方提示的本地化出口 SPI（p5-optional-modules D-002/D-006，架构设计 4.1）：
 * framework-web 错误输出经本接口随 Locale 取本地化提示；framework-i18n 提供实现，
 * 无实现时输出 Error 枚举默认文案，消除 web→i18n 依赖可能（Modules.md 3.3.1 同层不互依）。
 */
public interface PromptLocalizer {

    /**
     * 按错误码与 Locale 解析面向调用方的提示。
     *
     * @param errorCode 错误码（提示键可取 code 或语义键）
     * @param locale    请求 Locale（可空，实现按默认 Locale 处理）
     * @return 本地化提示；实现返回 {@code null} 时调用方回退 {@link ErrorCode#defaultMessage()}（DP-012 缺 key 口径）
     */
    String localize(ErrorCode errorCode, Locale locale);
}
