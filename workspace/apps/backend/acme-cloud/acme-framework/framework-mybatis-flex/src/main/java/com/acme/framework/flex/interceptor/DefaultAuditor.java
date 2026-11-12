package com.acme.framework.flex.interceptor;

import com.acme.common.context.UserContext;
import com.acme.framework.flex.config.FlexProperties;
import com.acme.framework.flex.support.Auditor;

/**
 * 缺省操作人解析（FR-008、6.2）：取 UserContext 操作人，缺失回退 {@code acme.flex.audit.system-operator}。
 */
public class DefaultAuditor implements Auditor {

    private final FlexProperties properties;

    public DefaultAuditor(FlexProperties properties) {
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
