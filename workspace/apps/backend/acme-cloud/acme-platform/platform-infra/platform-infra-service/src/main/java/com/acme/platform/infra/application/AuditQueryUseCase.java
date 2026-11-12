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
 */
@Service
public class AuditQueryUseCase {

    private final OpAuditEventRepository repository;

    public AuditQueryUseCase(OpAuditEventRepository repository) {
        this.repository = repository;
    }

    /** 分页查询（occurred_at 倒序，同毫秒按 id 倒序稳定排序）。 */
    public PageResult<OpAuditEvent> page(PageQuery query, String operatorId, String action,
                                         String traceId, Instant from, Instant to) {
        return doPage(query, operatorId, action, traceId, from, to);
    }

    /**
     * 全租户审计视图（consumer-auth-refresh-dataperm 数据权限落地）：
     * {@code @DataPermission} 切面兜底——无 infra:audit:list:all 权限直调本用例即 403；
     * 控制器路由为第一道（无权限走本人视图 page），本注解为纵深防御第二道。
     */
    @DataPermission(AuditDataPermissionKey.AUDIT_LIST_ALL)
    public PageResult<OpAuditEvent> pageAll(PageQuery query, String operatorId, String action,
                                            String traceId, Instant from, Instant to) {
        return doPage(query, operatorId, action, traceId, from, to);
    }

    private PageResult<OpAuditEvent> doPage(PageQuery query, String operatorId, String action,
                                            String traceId, Instant from, Instant to) {
        QueryWrapper wrapper = QueryWrapper.create();
        boolean hasCondition = false;
        if (operatorId != null && !operatorId.isBlank()) {
            wrapper.where(OpAuditEvent::getOperatorId).eq(operatorId.trim());
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
