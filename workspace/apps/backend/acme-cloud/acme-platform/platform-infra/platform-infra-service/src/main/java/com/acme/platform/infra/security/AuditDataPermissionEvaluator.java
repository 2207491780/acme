package com.acme.platform.infra.security;

import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataPermissionDecision;
import com.acme.framework.security.support.DataPermissionEvaluator;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import org.springframework.stereotype.Component;

/**
 * 审计数据权限评估器（consumer-auth-refresh-dataperm：framework-security 数据权限机制的业务落地）：
 * key {@code infra.audit.list.all} = 全租户审计视图——判定当前主体角色集是否具备
 * 权限点 {@code infra:audit:list:all}（经 AuthorizationClient 远程执行，权限目录归 platform-system）。
 * 无权限的调用者由控制器路由强制本人视图；本评估器对 pageAll 用例形成切面兜底
 * （无权限直调用例即 403 DATA_PERMISSION_DENIED）。评估器异常按拒绝处理（框架既有语义）。
 */
@Component
public class AuditDataPermissionEvaluator implements DataPermissionEvaluator {

    /** 全量审计视图数据权限键（与 AuditQueryUseCase.pageAll 标注一致）。 */
    public static final String AUDIT_LIST_ALL_KEY = "infra.audit.list.all";

    private final AuthorizationGateway authorizationGateway;

    public AuditDataPermissionEvaluator(AuthorizationGateway authorizationGateway) {
        this.authorizationGateway = authorizationGateway;
    }

    @Override
    public DataPermissionDecision evaluate(DataPermissionContext context) {
        if (!AUDIT_LIST_ALL_KEY.equals(context.key())) {
            // 未知数据权限键：拒绝（fail-closed，防越权直调）
            return DataPermissionDecision.DENY;
        }
        return authorizationGateway.hasPermission(com.acme.platform.system.api.SystemPermissions.INFRA_AUDIT_LIST_ALL)
                ? DataPermissionDecision.ALLOW
                : DataPermissionDecision.DENY;
    }
}
