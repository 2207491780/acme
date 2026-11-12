package com.acme.framework.doc.internal;

import org.springdoc.core.models.GroupedOpenApi;

/**
 * B/C 端分组（FR-014、11.5：{@code /admin/**}→Admin 组、{@code /app/**}→App 组，对齐 Admin/App 端别命名）。
 */
public final class EndGroups {

    /** Admin 组路径前缀。 */
    public static final String ADMIN_PREFIX = "/admin/**";

    /** App 组路径前缀。 */
    public static final String APP_PREFIX = "/app/**";

    private EndGroups() {
    }

    /** Admin 组（B 端）。 */
    public static GroupedOpenApi admin() {
        return GroupedOpenApi.builder().group("admin").pathsToMatch(ADMIN_PREFIX).build();
    }

    /** App 组（C 端）。 */
    public static GroupedOpenApi app() {
        return GroupedOpenApi.builder().group("app").pathsToMatch(APP_PREFIX).build();
    }
}
