package com.acme.framework.jpa.interceptor;

import com.acme.common.context.UserContext;
import com.acme.framework.jpa.config.JpaProperties;
import com.acme.framework.jpa.support.Auditor;

/**
 * 缺省操作人解析（FR-008、6.2）：取 UserContext 操作人，缺失回退 {@code acme.jpa.audit.system-operator}。
 */
public class DefaultAuditor implements Auditor {

    private final JpaProperties properties;

    public DefaultAuditor(JpaProperties properties) {
        this.properties = properties;
    }

    @Override
    public String currentOperator() {
        UserContext userContext = UserContext.get();
        if (userContext != null && userContext.userId() != null && !userContext.userId().isBlank()) {
            return userContext.userId();
        }
        return properties.getAudit().getSystemOperator();
    }
}
