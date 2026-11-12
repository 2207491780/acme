package com.acme.platform.infra.interfaces;

import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.infra.api.dto.AdminAuditEventView;
import com.acme.platform.infra.application.AuditQueryUseCase;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
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

/**
 * 审计事件 B 端查询接口（权限点 infra:audit:list；查询行为自身纳入审计，11.4 B 端全量审计）。
 * 数据权限两级（consumer-auth-refresh-dataperm）：具备 infra:audit:list:all → 全租户视图（pageAll，
 * 另受 @DataPermission 切面兜底）；否则强制本人视图（operatorId 归一为当前用户，12.4.1 租户过滤叠加）。
 */
@RestController
@RequestMapping("/admin/v1/infra/audit-events")
public class AuditEventController {

    private final AuditQueryUseCase queryUseCase;
    private final AuthorizationGateway authorizationGateway;

    public AuditEventController(AuditQueryUseCase queryUseCase, AuthorizationGateway authorizationGateway) {
        this.queryUseCase = queryUseCase;
        this.authorizationGateway = authorizationGateway;
    }

    /** 分页查询（时间范围 / 操作人 / 动作 / traceId 过滤；视图按数据权限路由）。 */
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
        PageResult<OpAuditEvent> result;
        if (authorizationGateway.hasPermission(SystemPermissions.INFRA_AUDIT_LIST_ALL)) {
            result = queryUseCase.pageAll(new PageQuery(page, size), operatorId, action, traceId, from, to);
        } else {
            // 本人视图：操作人强制归一为当前用户（行级数据权限，11.4 B 端审计按人收敛）
            result = queryUseCase.page(new PageQuery(page, size), currentOperatorId(), action, traceId, from, to);
        }
        List<AdminAuditEventView> records = result.records().stream().map(AuditEventController::view).toList();
        return new PageResult<>(records, result.total());
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
