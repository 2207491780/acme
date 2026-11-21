package com.acme.platform.workflow.api.dto;

import java.util.Map;

/**
 * 流程实例挂起请求（POST /{id}/suspend；reason 落 suspend_reason，缺省=ADMIN_SUSPEND）。
 */
public record ProcessInstanceSuspendRequest(String reason) {

    /** 缺省挂起原因。 */
    public static final String DEFAULT_REASON = "ADMIN_SUSPEND";

    /** 解析原因（空值取缺省）。 */
    public String resolveReason() {
        return reason == null || reason.isBlank() ? DEFAULT_REASON : reason.trim();
    }
}
