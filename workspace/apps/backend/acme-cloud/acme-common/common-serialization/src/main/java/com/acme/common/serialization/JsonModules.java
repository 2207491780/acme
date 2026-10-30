package com.acme.common.serialization;

import tools.jackson.databind.JacksonModule;

import java.util.List;

/**
 * 标准 Module 集合工厂（FR-024；BR-010 全链路共用同一 Module 集合）：
 * Long→String、时间格式与时区、枚举 code、脱敏、BigDecimal 规整。
 * 供 JsonMapperBuilderCustomizer / 测试装配共用（Modules.md 2.5.1、2.5.2）。
 *
 * <p>脱敏行为经 {@link Desensitize} 注解激活（架构设计 3.5.4），不需独立 Module。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class JsonModules {

    private JsonModules() {
    }

    /**
     * 返回标准 Module 集合（FR-024）。
     */
    public static List<JacksonModule> standard() {
        return List.of(
                new LongToStringModule(),
                new TimeFormatModule(),
                new EnumCodeModule(),
                new BigDecimalModule());
    }
}
