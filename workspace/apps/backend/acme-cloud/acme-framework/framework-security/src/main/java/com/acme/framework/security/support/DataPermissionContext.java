package com.acme.framework.security.support;

import java.util.Set;

/**
 * 数据权限评估上下文（FR-017，Q-D09）：仅承载判定要素，不含业务规则（8.1.2）。
 */
public record DataPermissionContext(String key, String methodName, String userId, String tenantId, Set<String> roles) {

    public DataPermissionContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
