package com.acme.framework.jpa.internal;

import com.acme.framework.core.support.ApplicationContexts;
import com.acme.framework.jpa.config.JpaProperties;

/**
 * 软删除开关读取（FR-009；容器未就绪按开启处理，缺省口径与配置默认值一致）。
 */
public final class SoftDeleteToggles {

    private SoftDeleteToggles() {
    }

    /** 软删除是否启用（{@code acme.jpa.soft-delete.enabled}，默认 true）。 */
    public static boolean enabled() {
        try {
            return ApplicationContexts.getBean(JpaProperties.class).getSoftDelete().isEnabled();
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
