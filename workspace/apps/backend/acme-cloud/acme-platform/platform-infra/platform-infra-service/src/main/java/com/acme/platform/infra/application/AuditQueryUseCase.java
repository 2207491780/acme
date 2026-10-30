package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.security.support.DataPermission;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
        Specification<OpAuditEvent> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (operatorId != null && !operatorId.isBlank()) {
                predicates.add(cb.equal(root.get("operatorId"), operatorId.trim()));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action.trim()));
            }
            if (traceId != null && !traceId.isBlank()) {
                predicates.add(cb.equal(root.get("traceId"), traceId.trim()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<OpAuditEvent> page = repository.findAll(spec,
                PageRequest.of(query.page() - 1, query.size(), Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }

    /** 数据权限键（与 AuditDataPermissionEvaluator 对齐）。 */
    static final class AuditDataPermissionKey {

        static final String AUDIT_LIST_ALL = "infra.audit.list.all";

        private AuditDataPermissionKey() {
        }
    }
}
