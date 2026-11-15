package com.acme.platform.infra.interfaces;

import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataScopeDecision;
import com.acme.platform.infra.api.dto.AdminAuditEventView;
import com.acme.platform.infra.application.AuditQueryUseCase;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.security.AuditDataPermissionEvaluator;
import com.acme.platform.infra.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 审计事件 B 端查询接口（权限点 infra:audit:list；查询行为自身纳入审计，11.4 B 端全量审计）。
 * 数据权限路由（consumer-auth-refresh-dataperm + REQ-20261114-120104 scope 模型）：
 * ALL 档（具备 infra:audit:list:all）→ 全租户视图（pageAll，另受 @DataPermission 切面兜底）；
 * 非受限主体按五档过滤——SELF 仅本人（原语义），DEPT/DEPT_AND_CHILD/CUSTOM 按可见用户集
 * （operator_id 集合过滤），受限档可见集为空 → 空页 fail-closed（12.4.1 租户过滤叠加）。
 */
@RestController
@RequestMapping("/admin/v1/infra/audit-events")
public class AuditEventController {

    private final AuditQueryUseCase queryUseCase;
    private final AuditDataPermissionEvaluator dataPermissionEvaluator;

    public AuditEventController(AuditQueryUseCase queryUseCase,
                                AuditDataPermissionEvaluator dataPermissionEvaluator) {
        this.queryUseCase = queryUseCase;
        this.dataPermissionEvaluator = dataPermissionEvaluator;
    }

    /** 分页查询（时间范围 / 操作人 / 动作 / traceId 过滤；视图按数据权限档位路由）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.INFRA_AUDIT_LIST)
    @AuditLog(action = "infra.audit.list", targetType = "auditEvent")
    public PageResult<AdminAuditEventView> page(
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) Integer size,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        DataScopeDecision scope = dataPermissionEvaluator.resolve(scopeContext());
        PageResult<OpAuditEvent> result;
        if (scope.unrestricted()) {
            // ALL 档：全租户视图（pageAll 切面兜底二次判定，纵深防御语义不变）
            result = queryUseCase.pageAll(new PageQuery(page, size), operatorId, action, traceId, from, to);
        } else {
            Set<String> visibleOperatorIds = scope.visibleUserIds();
            if (visibleOperatorIds.isEmpty()) {
                // 受限档可见集为空：fail-closed 空页（不落 IN () 空条件）
                return PageResult.empty();
            }
            // SELF 档=本人视图（原语义：operatorId 入参强制归一本人）；部门档=可见用户集过滤，
            // 请求 operatorId 叠加收窄（交集语义）
            String scopedOperatorId = scope.scope() == com.acme.framework.security.support.DataScope.SELF
                    ? currentOperatorId() : operatorId;
            result = queryUseCase.page(new PageQuery(page, size), scopedOperatorId, visibleOperatorIds,
                    action, traceId, from, to);
        }
        List<AdminAuditEventView> records = result.records().stream().map(AuditEventController::view).toList();
        return new PageResult<>(records, result.total());
    }

    /** 数据权限评估上下文（与 DataPermissionAspect 组装同款要素）。 */
    private DataPermissionContext scopeContext() {
        UserContext user = UserContext.get();
        com.acme.common.context.TenantContext tenant = com.acme.common.context.TenantContext.get();
        return new DataPermissionContext(AuditDataPermissionEvaluator.AUDIT_LIST_ALL_KEY,
                "AuditEventController.page",
                user == null ? null : user.userId(),
                tenant == null ? null : tenant.tenantId(),
                user == null ? Set.of() : user.roles());
    }

    private static String currentOperatorId() {
        UserContext context = UserContext.get();
        return context == null ? null : context.userId();
    }

    /** 实体 → B 端视图（11.5.3 接口层转换）。 */
    private static AdminAuditEventView view(OpAuditEvent row) {
        return new AdminAuditEventView(
                String.valueOf(row.getId()), row.getEventId(), row.getTraceId(), row.getSpanId(),
                row.getTenantId(), row.getOperatorId(), row.getChannel(), row.getAction(),
                row.getTargetType(), row.getTargetId(), row.getContent(),
                row.getOccurredAt(), row.getCreatedAt());
    }
}
