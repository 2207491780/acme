package com.acme.framework.jackson.internal;

import com.acme.common.serialization.JsonModules;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;

/**
 * Boot 装配点（FR-007、Modules.md 2.5.2）：注册 {@link JsonModules#standard()} 标准 Module 集后
 * 再注册 {@code @AcmeJacksonComponent} 组件（FR-008；与标准集合并存、互不覆盖，取舍 8）。
 * 全链路共用注入的 JsonMapper，不另造 ObjectMapper（BR-017）。
 */
public final class AcmeJsonMapperCustomizer implements JsonMapperBuilderCustomizer {

    private final Collection<Object> components;

    public AcmeJsonMapperCustomizer(Collection<Object> components) {
        this.components = components;
    }

    @Override
    public void customize(JsonMapper.Builder builder) {
        List<JacksonModule> standard = JsonModules.standard();
        for (JacksonModule module : standard) {
            builder.addModule(module);
        }
        if (!components.isEmpty()) {
            builder.addModule(JacksonComponentModules.of(components));
        }
    }
}
