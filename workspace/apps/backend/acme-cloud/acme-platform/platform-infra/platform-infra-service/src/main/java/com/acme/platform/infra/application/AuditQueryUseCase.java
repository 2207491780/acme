package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.security.support.DataPermission;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 审计事件查询用例（B 端管理视图；本租户自动过滤 12.4.1）：
 * 支持时间范围 / 操作人 / 动作 / traceId 过滤，按发生时间倒序分页。
 * REQ-20261114-120104：page 增补 visibleOperatorIds 集合过滤（数据权限 scope 模型示范域，
 * 非受限档传 null 不过滤）。
 * REQ-20261117-061017 确认④：同步导出（上限 10000 行并受 acme.excel.max-rows 收窄，超限截断），
 * 数据权限路由与 page/pageAll 同径。
 */
@Service
public class AuditQueryUseCase {

    private final OpAuditEventRepository repository;
    private final ExcelProperties excelProperties;

    public AuditQueryUseCase(OpAuditEventRepository repository, ExcelProperties excelProperties) {
        this.repository = repository;
        this.excelProperties = excelProperties;
    }

    /** 分页查询（occurred_at 倒序，同毫秒按 id 倒序稳定排序；visibleOperatorIds 非空=数据权限集合过滤）。 */
    public PageResult<OpAuditEvent> page(PageQuery query, String operatorId,
                                         java.util.Collection<String> visibleOperatorIds,
                                         String action, String traceId, Instant from, Instant to) {
        return doPage(query, operatorId, visibleOperatorIds, action, traceId, from, to);
    }

    /**
     * 全租户审计视图（consumer-auth-refresh-dataperm 数据权限落地）：
     * {@code @DataPermission} 切面兜底——无 infra:audit:list:all 权限直调本用例即 403；
     * 控制器路由为第一道（无权限走档位过滤视图 page），本注解为纵深防御第二道。
     */
    @DataPermission(AuditDataPermissionKey.AUDIT_LIST_ALL)
    public PageResult<OpAuditEvent> pageAll(PageQuery query, String operatorId, String action,
                                            String traceId, Instant from, Instant to) {
        return doPage(query, operatorId, null, action, traceId, from, to);
    }

    /** 导出（数据权限档位过滤视图；上限 10000 行并受 acme.excel.max-rows 收窄，超限截断）。 */
    public ExportResult<OpAuditEvent> export(String operatorId,
                                             java.util.Collection<String> visibleOperatorIds,
                                             String action, String traceId, Instant from, Instant to) {
        return doExport(operatorId, visibleOperatorIds, action, traceId, from, to);
    }

    /** 全租户审计导出（@DataPermission 切面兜底同 pageAll；控制器路由为第一道）。 */
    @DataPermission(AuditDataPermissionKey.AUDIT_LIST_ALL)
    public ExportResult<OpAuditEvent> exportAll(String operatorId, String action,
                                                String traceId, Instant from, Instant to) {
        return doExport(operatorId, null, action, traceId, from, to);
    }

    private ExportResult<OpAuditEvent> doExport(String operatorId,
                                                java.util.Collection<String> visibleOperatorIds,
                                                String action, String traceId, Instant from, Instant to) {
        long cap = ExportResult.cap(excelProperties);
        List<OpAuditEvent> fetched = repository
                .findAll(filterWrapper(operatorId, visibleOperatorIds, action, traceId, from, to)
                        .orderBy(OpAuditEvent::getOccurredAt, false)
                        .orderBy(OpAuditEvent::getId, false), 1, (int) (cap + 1)).records();
        return ExportResult.of(fetched, cap);
    }

    private PageResult<OpAuditEvent> doPage(PageQuery query, String operatorId,
                                            java.util.Collection<String> visibleOperatorIds,
                                            String action, String traceId, Instant from, Instant to) {
        QueryWrapper wrapper = filterWrapper(operatorId, visibleOperatorIds, action, traceId, from, to)
                .orderBy(OpAuditEvent::getOccurredAt, false)
                .orderBy(OpAuditEvent::getId, false);
        return repository.findAll(wrapper, query.page(), query.size());
    }

    private QueryWrapper filterWrapper(String operatorId,
                                       java.util.Collection<String> visibleOperatorIds,
                                       String action, String traceId, Instant from, Instant to) {
        QueryWrapper wrapper = QueryWrapper.create();
        boolean hasCondition = false;
        if (operatorId != null && !operatorId.isBlank()) {
            wrapper.where(OpAuditEvent::getOperatorId).eq(operatorId.trim());
            hasCondition = true;
        }
        // 数据权限集合过滤（scope 模型示范域）：空集不落 IN () 条件（空可见集由控制器短路）
        if (visibleOperatorIds != null && !visibleOperatorIds.isEmpty()) {
            if (hasCondition) {
                wrapper.and(OpAuditEvent::getOperatorId).in(visibleOperatorIds);
            } else {
                wrapper.where(OpAuditEvent::getOperatorId).in(visibleOperatorIds);
            }
            hasCondition = true;
        }
        if (action != null && !action.isBlank()) {
            if (hasCondition) {
                wrapper.and(OpAuditEvent::getAction).eq(action.trim());
            } else {
                wrapper.where(OpAuditEvent::getAction).eq(action.trim());
                hasCondition = true;
            }
        }
        if (traceId != null && !traceId.isBlank()) {
            if (hasCondition) {
                wrapper.and(OpAuditEvent::getTraceId).eq(traceId.trim());
            } else {
                wrapper.where(OpAuditEvent::getTraceId).eq(traceId.trim());
                hasCondition = true;
            }
        }
        if (from != null) {
            if (hasCondition) {
                wrapper.and(OpAuditEvent::getOccurredAt).ge(from);
            } else {
                wrapper.where(OpAuditEvent::getOccurredAt).ge(from);
                hasCondition = true;
            }
        }
        if (to != null) {
            if (hasCondition) {
                wrapper.and(OpAuditEvent::getOccurredAt).le(to);
            } else {
                wrapper.where(OpAuditEvent::getOccurredAt).le(to);
            }
        }
        return wrapper;
    }

    /** 数据权限键（与 AuditDataPermissionEvaluator 对齐）。 */
    static final class AuditDataPermissionKey {

        static final String AUDIT_LIST_ALL = "infra.audit.list.all";

        private AuditDataPermissionKey() {
        }
    }
}
