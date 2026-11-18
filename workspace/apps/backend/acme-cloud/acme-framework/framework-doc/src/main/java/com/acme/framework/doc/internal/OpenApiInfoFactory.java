package com.acme.framework.doc.internal;

import com.acme.framework.doc.config.DocProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI 元信息装配（FR-014：title/version 等可配置）。
 */
public final class OpenApiInfoFactory {

    private OpenApiInfoFactory() {
    }

    /** 按配置装配元信息。 */
    public static OpenAPI create(DocProperties properties) {
        return new OpenAPI().info(new Info()
                .title(properties.getTitle())
                .version(properties.getVersion()));
    }
}
