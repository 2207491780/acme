package com.acme.framework.doc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接口文档配置（FR-014/015、8.1.3 全字段默认值；架构设计 15 章）。
 * 路径与开关经 {@code DocEnvironmentPostProcessor} 桥接 springdoc（acme.doc.* 单一配置源）。
 */
@ConfigurationProperties(prefix = "acme.doc")
public class DocProperties {

    /** 模块开关（3.5 可关闭性；关闭后端点不可访问，NFR-004）。 */
    private boolean enabled = true;

    /** 文档标题。 */
    private String title = "Acme API";

    /** 文档版本。 */
    private String version = "1.0.0";

    /** api-docs 端点路径。 */
    private String apiDocsPath = "/v3/api-docs";

    /** Swagger UI 路径。 */
    private String swaggerUiPath = "/swagger-ui";

    /** 按端分组呈现（B/C，11.5；{@code /admin/**}→Admin、{@code /app/**}→App）。 */
    private boolean groupByEnd = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getApiDocsPath() {
        return apiDocsPath;
    }

    public void setApiDocsPath(String apiDocsPath) {
        this.apiDocsPath = apiDocsPath;
    }

    public String getSwaggerUiPath() {
        return swaggerUiPath;
    }

    public void setSwaggerUiPath(String swaggerUiPath) {
        this.swaggerUiPath = swaggerUiPath;
    }

    public boolean isGroupByEnd() {
        return groupByEnd;
    }

    public void setGroupByEnd(boolean groupByEnd) {
        this.groupByEnd = groupByEnd;
    }
}
