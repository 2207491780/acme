package com.acme.platform.infra.security;

import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataPermissionDecision;
import com.acme.framework.security.support.DataScope;
import com.acme.framework.security.support.DataScopeDecision;
import com.acme.framework.security.support.DataScopeResolver;
import com.acme.framework.security.support.DataPermissionEvaluator;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import com.acme.platform.infra.infrastructure.client.DataScopeGateway;
import com.acme.platform.system.api.DataScopeView;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 审计数据权限评估器（consumer-auth-refresh-dataperm + REQ-20261114-120104 scope 模型改造）：
 * <ul>
 *   <li>{@link DataPermissionEvaluator}（ALLOW/DENY 门禁，行为不变）：key
 *       {@code infra.audit.list.all}=全租户审计视图——判定当前主体角色集是否具备权限点
 *       {@code infra:audit:list:all}（经 AuthorizationGateway 远程执行）；无权限的调用者由控制器
 *       路由强制本人视图；对 pageAll 用例形成切面兜底（无权限直调用例即 403 DATA_PERMISSION_DENIED）。</li>
 *   <li>{@link DataScopeResolver}（scope 模型，AC-5）：审计视图档位映射——
 *       具备 infra:audit:list:all → ALL 档（原「全租户视图」语义）；否则取主体角色档，
 *       DEPT/DEPT_AND_CHILD/CUSTOM 按档位集合过滤（可见用户集由 platform-system 内部端点展开），
 *       SELF 档仅本人；角色档为 ALL 但无权限点 → 收敛 SELF（ALL 档须经专用权限点，
 *       V9 缺省列不放大存量审计视图——行为兼容映射，见交付说明）；解析失败 fail-closed SELF。</li>
 * </ul>
 * 评估器异常按拒绝处理（框架既有语义）。
 */
@Component
public class AuditDataPermissionEvaluator implements DataPermissionEvaluator, DataScopeResolver {

    /** 全量审计视图数据权限键（与 AuditQueryUseCase.pageAll 标注一致）。 */
    public static final String AUDIT_LIST_ALL_KEY = "infra.audit.list.all";

    private final AuthorizationGateway authorizationGateway;
    private final DataScopeGateway dataScopeGateway;

    public AuditDataPermissionEvaluator(AuthorizationGateway authorizationGateway,
                                        DataScopeGateway dataScopeGateway) {
        this.authorizationGateway = authorizationGateway;
        this.dataScopeGateway = dataScopeGateway;
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

    /**
     * 审计域数据范围解析（控制器路由用）：ALL 档=全租户视图路由 pageAll；其余档位返回过滤条件载体
     * （SELF=本人，部门档=可见用户集）。
     */
    @Override
    public DataScopeDecision resolve(DataPermissionContext context) {
        String userId = context.userId();
        if (authorizationGateway.hasPermission(com.acme.platform.system.api.SystemPermissions.INFRA_AUDIT_LIST_ALL)) {
            return DataScopeDecision.all();
        }
        DataScopeView view = dataScopeGateway.currentDataScope();
        if (view == null) {
            return selfDecision(userId);
        }
        DataScope scope = Optional.ofNullable(DataScope.parse(view.scope())).orElse(DataScope.SELF);
        // 行为兼容映射：角色档 ALL 但无 infra:audit:list:all 权限点 → 收敛 SELF（缺省列不放大存量审计视图）
        if (scope == DataScope.ALL) {
            return selfDecision(userId);
        }
        Set<Long> deptIds = new LinkedHashSet<>(view.deptIds());
        Set<String> userIds = new LinkedHashSet<>(view.userIds());
        if (deptIds.isEmpty() && userIds.isEmpty()) {
            return selfDecision(userId);
        }
        return DataScopeDecision.of(scope, deptIds, userIds);
    }

    private static DataScopeDecision selfDecision(String userId) {
        return userId == null || userId.isBlank()
                ? new DataScopeDecision(DataScope.SELF, Set.of(), Set.of())
                : DataScopeDecision.of(DataScope.SELF, Set.of(), Set.of(userId));
    }
}
