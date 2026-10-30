package com.acme.common.serialization.internal;

import com.acme.common.serialization.Desensitize;
import com.acme.common.serialization.DesensitizeStrategy;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * 脱敏序列化器（FR-025）：按策略掩码后输出（架构设计 3.5.4）。经 {@link Desensitize} 注解激活。
 * 策略缺失与非法输入显式失败，不静默脱敏、不静默放行（FR-025 异常分支；DP-010 定稿）。
 */
public final class DesensitizeSerializer extends ValueSerializer<Object> {

    private static final String MASK_SEGMENT = "****";
    private static final String ID_CARD_MIDDLE = "********";

    private final DesensitizeStrategy strategy;

    /** 供 Jackson 经 {@link Desensitize} 元注解实例化；策略经 {@link #createContextual} 补齐。 */
    public DesensitizeSerializer() {
        this.strategy = null;
    }

    public DesensitizeSerializer(DesensitizeStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public ValueSerializer<?> createContextual(SerializationContext ctxt, BeanProperty property) {
        if (property != null) {
            Desensitize annotation = property.getAnnotation(Desensitize.class);
            if (annotation != null) {
                return new DesensitizeSerializer(annotation.strategy());
            }
        }
        return this;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(mask(String.valueOf(value), strategy));
    }

    /**
     * 按策略掩码（FR-025）。策略缺失显式失败（DP-010 定稿）；逐策略掩码规则与非法输入细则见
     * {@link DesensitizeStrategy} 各枚举值 javadoc。
     */
    static String mask(String raw, DesensitizeStrategy strategy) {
        if (strategy == null) {
            throw new IllegalStateException("脱敏策略缺失，不得静默脱敏或静默放行");
        }
        return switch (strategy) {
            case PHONE -> maskEnds(raw, 3, 4, MASK_SEGMENT, 7, "手机号长度不足");
            case ID_CARD -> maskEnds(raw, 6, 4, ID_CARD_MIDDLE, 10, "身份证号长度不足");
            case EMAIL -> maskEmail(raw);
            case BANK_CARD -> maskTail(raw, 4, 8, "银行卡号长度不足");
            case CHINESE_NAME -> maskChineseName(raw);
            case MASK_ALL -> MASK_SEGMENT;
        };
    }

    /** 保留首尾两端、中间替换为固定掩码段（不暴露原文长度）。 */
    private static String maskEnds(String raw, int keepHead, int keepTail, String middle, int minLength, String failureHint) {
        if (raw == null || raw.length() < minLength) {
            throw new IllegalArgumentException(failureHint + "，无法掩码：" + raw);
        }
        return raw.substring(0, keepHead) + middle + raw.substring(raw.length() - keepTail);
    }

    /** 仅保留尾部若干位，前缀替换为固定掩码段。 */
    private static String maskTail(String raw, int keepTail, int minLength, String failureHint) {
        if (raw == null || raw.length() < minLength) {
            throw new IllegalArgumentException(failureHint + "，无法掩码：" + raw);
        }
        return MASK_SEGMENT + raw.substring(raw.length() - keepTail);
    }

    /** 保留本地部分首字符与完整域名，本地部分其余替换为固定掩码段。 */
    private static String maskEmail(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("邮箱为空，无法掩码");
        }
        int at = raw.indexOf('@');
        if (at <= 0 || at == raw.length() - 1) {
            throw new IllegalArgumentException("邮箱格式不符，无法掩码：" + raw);
        }
        return raw.charAt(0) + MASK_SEGMENT + raw.substring(at);
    }

    /** 保留首字符（姓），其余逐字替换为星号。 */
    private static String maskChineseName(String raw) {
        if (raw == null || raw.length() < 2) {
            throw new IllegalArgumentException("中文姓名长度不足，无法掩码：" + raw);
        }
        return raw.charAt(0) + "*".repeat(raw.length() - 1);
    }
}
