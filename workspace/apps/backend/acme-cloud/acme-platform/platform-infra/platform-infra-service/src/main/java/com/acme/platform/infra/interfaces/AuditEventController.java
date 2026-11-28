package com.acme.platform.infra.interfaces;

import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.framework.excel.internal.ExcelHttpExporter;
import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataScopeDecision;
import com.acme.platform.infra.api.dto.AdminAuditEventView;
import com.acme.platform.infra.application.AuditEventExportRow;
import com.acme.platform.infra.application.AuditQueryUseCase;
import com.acme.platform.infra.application.ExportResult;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.security.AuditDataPermissionEvaluator;
import com.acme.framework.web.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.servlet.http.HttpServletResponse;
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
 * REQ-20261117-061017 确认④：同步导出（xlsx 附件，上限 10000 行，超限截断
 * X-Export-Truncated: true + 文件名 .part 后缀），数据权限路由与分页同径。
 */
@RestController
@RequestMapping("/admin/v1/infra/audit-events")
public class AuditEventController {

    private static final String EXPORT_FILE_NAME = "audit-events.xlsx";

    private static final String EXPORT_PART_SUFFIX = ".part";

    private final AuditQueryUseCase queryUseCase;
    private final AuditDataPermissionEvaluator dataPermissionEvaluator;
    private final ExcelHttpExporter excelHttpExporter;

    public AuditEventController(AuditQueryUseCase queryUseCase,
                                AuditDataPermissionEvaluator dataPermissionEvaluator,
                                ExcelHttpExporter excelHttpExporter) {
        this.queryUseCase = queryUseCase;
        this.dataPermissionEvaluator = dataPermissionEvaluator;
        this.excelHttpExporter = excelHttpExporter;
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
        PageResult<OpAuditEvent> result = resolve(new PageQuery(page, size), operatorId, action,
                traceId, from, to);
        List<AdminAuditEventView> records = result.records().stream().map(AuditEventController::view).toList();
        return new PageResult<>(records, result.total());
    }

    /**
     * 同步导出（确认④：xlsx 附件豁免信封；上限 10000 行——超限截断并响应
     * X-Export-Truncated: true，文件名 .part 后缀；数据权限路由与分页同径）。
     */
    @GetMapping("/export")
    @RequirePermission(SystemPermissions.INFRA_AUDIT_LIST)
    public void export(HttpServletResponse response,
                       @RequestParam(required = false) String operatorId,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String traceId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        ExportResult<OpAuditEvent> batch = resolveExport(operatorId, action, traceId, from, to);
        List<AuditEventExportRow> rows = batch.records().stream()
                .map(AuditEventExportRow::from).toList();
        if (batch.truncated()) {
            response.setHeader("X-Export-Truncated", "true");
        }
        excelHttpExporter.export(response,
                batch.truncated() ? EXPORT_FILE_NAME + EXPORT_PART_SUFFIX : EXPORT_FILE_NAME,
                rows, AuditEventExportRow.class);
    }

    /** 数据权限档位路由（分页/导出共用；与既有 page 语义一致）。 */
    private PageResult<OpAuditEvent> resolve(PageQuery query, String operatorId, String action,
                                             String traceId, Instant from, Instant to) {
        DataScopeDecision scope = dataPermissionEvaluator.resolve(scopeContext());
        if (scope.unrestricted()) {
            // ALL 档：全租户视图（pageAll 切面兜底二次判定，纵深防御语义不变）
            return queryUseCase.pageAll(query, operatorId, action, traceId, from, to);
        }
        Set<String> visibleOperatorIds = scope.visibleUserIds();
        if (visibleOperatorIds.isEmpty()) {
            // 受限档可见集为空：fail-closed 空页（不落 IN () 空条件）
            return PageResult.empty();
        }
        // SELF 档=本人视图（原语义：operatorId 入参强制归一本人）；部门档=可见用户集过滤，
        // 请求 operatorId 叠加收窄（交集语义）
        String scopedOperatorId = scope.scope() == com.acme.framework.security.support.DataScope.SELF
                ? currentOperatorId() : operatorId;
        return queryUseCase.page(query, scopedOperatorId, visibleOperatorIds, action, traceId, from, to);
    }

    /** 数据权限档位路由（导出面；路由语义与 resolve 一致，返回截断标记承载的导出批）。 */
    private ExportResult<OpAuditEvent> resolveExport(String operatorId, String action,
                                                     String traceId, Instant from, Instant to) {
        DataScopeDecision scope = dataPermissionEvaluator.resolve(scopeContext());
        if (scope.unrestricted()) {
            return queryUseCase.exportAll(operatorId, action, traceId, from, to);
        }
        Set<String> visibleOperatorIds = scope.visibleUserIds();
        if (visibleOperatorIds.isEmpty()) {
            return new ExportResult<>(List.of(), false);
        }
        String scopedOperatorId = scope.scope() == com.acme.framework.security.support.DataScope.SELF
                ? currentOperatorId() : operatorId;
        return queryUseCase.export(scopedOperatorId, visibleOperatorIds, action, traceId, from, to);
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
