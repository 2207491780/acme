package com.acme.platform.system.application;

import java.util.Map;
import java.util.Set;

/**
 * sys_config 租户域配置键单一登记（REQ-20261127-161433 P2-03：键词汇与值校验类型单源化）。
 *
 * <p>此前键词汇硬编码于 {@code TenantInitTemplateUseCase.KNOWN_CONFIG_KEYS} 与
 * {@code TenantInitUseCase.applyConfigEntry} 的 switch case 两处（双改漏改风险）；现统一由本类
 * 派生：键词汇常量供开通装配 switch case 引用，{@link #VALUE_TYPES} 供模板保存的值校验分发。
 * 新增/改名键只改本类（编译期两处引用同步强制）。</p>
 */
public final class TenantConfigKeys {

    /** 值校验类型（模板保存时按此分发；FREE_TEXT=字符串键放行任意值，null=清除）。 */
    public enum ValueType {
        /** 布尔字面量（parseBoolean 口径）。 */
        BOOLEAN,
        /** 锁屏闲置毫秒（复用系统配置取值集 ALLOWED_IDLE_TIMEOUTS_MS）。 */
        LOCK_IDLE_TIMEOUT,
        /** 字符串键：任意值放行。 */
        FREE_TEXT
    }

    public static final String WATERMARK_ENABLED = "watermarkEnabled";
    public static final String CAPTCHA_ENABLED = "captchaEnabled";
    public static final String LOCK_IDLE_TIMEOUT_MS = "lockIdleTimeoutMs";
    public static final String FOOTER_VISIBLE = "footerVisible";
    public static final String FOOTER_COPYRIGHT = "footerCopyright";
    public static final String WATERMARK_TEMPLATE = "watermarkTemplate";

    /** 键 → 值校验类型（唯一登记处；键词汇全集 = 本 Map 键集）。 */
    public static final Map<String, ValueType> VALUE_TYPES = Map.of(
            WATERMARK_ENABLED, ValueType.BOOLEAN,
            CAPTCHA_ENABLED, ValueType.BOOLEAN,
            LOCK_IDLE_TIMEOUT_MS, ValueType.LOCK_IDLE_TIMEOUT,
            FOOTER_VISIBLE, ValueType.BOOLEAN,
            FOOTER_COPYRIGHT, ValueType.FREE_TEXT,
            WATERMARK_TEMPLATE, ValueType.FREE_TEXT);

    /** 已知键词汇（六键；= VALUE_TYPES 键集视图，与开通装配 switch case 同源）。 */
    public static final Set<String> KNOWN = VALUE_TYPES.keySet();

    private TenantConfigKeys() {
    }
}
