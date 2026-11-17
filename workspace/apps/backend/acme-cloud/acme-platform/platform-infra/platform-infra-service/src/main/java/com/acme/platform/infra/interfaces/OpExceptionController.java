package com.acme.platform.infra.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.framework.excel.internal.ExcelHttpExporter;
import com.acme.platform.infra.api.dto.AdminOpExceptionDetailView;
import com.acme.platform.infra.api.dto.AdminOpExceptionView;
import com.acme.platform.infra.application.OpExceptionExportRow;
import com.acme.platform.infra.application.OpExceptionUseCase;
import com.acme.platform.infra.domain.OpException;
import com.acme.platform.infra.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 运行异常日志 B 端接口（REQ-20261117-061017 §API；权限点 infra:exception:list，接口级权限生效）：
 * 分页筛选（service/traceId/exceptionType/时间区间）、详情（含 stackSummary 全文）与同步导出
 * （xlsx 附件，上限 10000 行，超限截断 X-Export-Truncated: true + 文件名 .part 后缀）。
 * 查询/详情行为自身纳入审计（11.4 B 端全量审计，AuditEventController 先例）。
 */
@RestController
@RequestMapping("/admin/v1/infra/op-exceptions")
public class OpExceptionController {

    private static final String EXPORT_FILE_NAME = "op-exceptions.xlsx";

    private static final String EXPORT_PART_SUFFIX = ".part";

    private final OpExceptionUseCase opExceptionUseCase;
    private final ExcelHttpExporter excelHttpExporter;

    public OpExceptionController(OpExceptionUseCase opExceptionUseCase,
                                 ExcelHttpExporter excelHttpExporter) {
        this.opExceptionUseCase = opExceptionUseCase;
        this.excelHttpExporter = excelHttpExporter;
    }

    /** 分页筛选查询（本租户自动过滤；occurred_at 倒序）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.INFRA_EXCEPTION_LIST)
    @AuditLog(action = "infra.opException.query", targetType = "opException")
    public PageResult<AdminOpExceptionView> page(
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) Integer size,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String exceptionType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        PageResult<OpException> result = opExceptionUseCase.page(new PageQuery(page, size),
                service, traceId, exceptionType, from, to);
        List<AdminOpExceptionView> records = result.records().stream()
                .map(OpExceptionController::view).toList();
        return new PageResult<>(records, result.total());
    }

    /** 详情（含 stackSummary 全文；不存在 OP_EXCEPTION_NOT_FOUND）。 */
    @GetMapping("/{exceptionId}")
    @RequirePermission(SystemPermissions.INFRA_EXCEPTION_LIST)
    @AuditLog(action = "infra.opException.query", targetType = "opException")
    public AdminOpExceptionDetailView detail(@PathVariable Long exceptionId) {
        return detailView(opExceptionUseCase.get(exceptionId));
    }

    /**
     * 同步导出（确认④：xlsx 附件豁免信封；上限 10000 行——超限截断并响应
     * X-Export-Truncated: true，文件名 .part 后缀）。
     */
    @GetMapping("/export")
    @RequirePermission(SystemPermissions.INFRA_EXCEPTION_LIST)
    public void export(HttpServletResponse response,
                       @RequestParam(required = false) String service,
                       @RequestParam(required = false) String traceId,
                       @RequestParam(required = false) String exceptionType,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var batch = opExceptionUseCase.export(service, traceId, exceptionType, from, to);
        List<OpExceptionExportRow> rows = batch.records().stream().map(OpExceptionExportRow::from).toList();
        if (batch.truncated()) {
            response.setHeader("X-Export-Truncated", "true");
        }
        excelHttpExporter.export(response,
                batch.truncated() ? EXPORT_FILE_NAME + EXPORT_PART_SUFFIX : EXPORT_FILE_NAME,
                rows, OpExceptionExportRow.class);
    }

    /** 实体 → 列表视图（11.5.3 接口层转换；id wire 字符串）。 */
    private static AdminOpExceptionView view(OpException exception) {
        return new AdminOpExceptionView(
                String.valueOf(exception.getId()), exception.getService(), exception.getTraceId(),
                exception.getSpanId(), exception.getTenantId(), exception.getOperatorId(),
                exception.getMethod(), exception.getPath(), exception.getStatusCode(),
                exception.getExceptionType(), exception.getMessage(), exception.getOccurredAt());
    }

    /** 实体 → 详情视图（stackSummary 全文随行）。 */
    private static AdminOpExceptionDetailView detailView(OpException exception) {
        return new AdminOpExceptionDetailView(
                String.valueOf(exception.getId()), exception.getService(), exception.getTraceId(),
                exception.getSpanId(), exception.getTenantId(), exception.getOperatorId(),
                exception.getMethod(), exception.getPath(), exception.getStatusCode(),
                exception.getExceptionType(), exception.getMessage(),
                exception.getStackSummary(), exception.getOccurredAt());
    }
}
