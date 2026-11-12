package com.acme.framework.doc.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.doc.internal.EndGroups;
import com.acme.framework.doc.internal.OpenApiInfoFactory;
import org.springdoc.core.models.GroupedOpenApi;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * framework-doc 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * springdoc 服务侧聚合（Q-008 仅服务侧，跨服务聚合不属本期）；
 * 可关闭性经 {@code DocEnvironmentPostProcessor} 桥接（FR-015/AC-015）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("doc")
@EnableConfigurationProperties(DocProperties.class)
public class DocAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** OpenAPI 元信息（FR-014；可注册 OpenAPI 覆盖）。 */
    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI openAPI(DocProperties properties) {
        return OpenApiInfoFactory.create(properties);
    }

    /** Admin 组（B 端呈现，FR-014、11.5；group-by-end）。 */
    @Bean
    @ConditionalOnProperty(prefix = "acme.doc", name = "group-by-end", havingValue = "true", matchIfMissing = true)
    public GroupedOpenApi adminEndGroup() {
        return EndGroups.admin();
    }

    /** App 组（C 端呈现，FR-014、11.5；group-by-end）。 */
    @Bean
    @ConditionalOnProperty(prefix = "acme.doc", name = "group-by-end", havingValue = "true", matchIfMissing = true)
    public GroupedOpenApi appEndGroup() {
        return EndGroups.app();
    }
}
