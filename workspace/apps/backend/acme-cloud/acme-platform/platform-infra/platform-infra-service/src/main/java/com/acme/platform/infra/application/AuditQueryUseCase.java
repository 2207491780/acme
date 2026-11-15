package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.security.support.DataPermission;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 审计事件查询用例（B 端管理视图；本租户自动过滤 12.4.1）：
 * 支持时间范围 / 操作人 / 动作 / traceId 过滤，按发生时间倒序分页。
 * REQ-20261114-120104：page 增补 visibleOperatorIds 集合过滤（数据权限 scope 模型示范域，
 * 非受限档传 null 不过滤）。
 */
@Service
public class AuditQueryUseCase {

    private final OpAuditEventRepository repository;

    public AuditQueryUseCase(OpAuditEventRepository repository) {
        this.repository = repository;
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

    private PageResult<OpAuditEvent> doPage(PageQuery query, String operatorId,
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
        wrapper.orderBy(OpAuditEvent::getOccurredAt, false).orderBy(OpAuditEvent::getId, false);
        return repository.findAll(wrapper, query.page(), query.size());
    }

    /** 数据权限键（与 AuditDataPermissionEvaluator 对齐）。 */
    static final class AuditDataPermissionKey {

        static final String AUDIT_LIST_ALL = "infra.audit.list.all";

        private AuditDataPermissionKey() {
        }
    }
}
