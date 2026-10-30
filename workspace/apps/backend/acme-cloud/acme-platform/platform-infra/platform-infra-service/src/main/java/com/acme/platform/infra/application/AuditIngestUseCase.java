package com.acme.platform.infra.application;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 审计事件落库用例（10.2.1 事务边界在应用层、仅覆盖本服务库；事务内无远程调用 M-05）：
 * 事件必填字段校验（毒消息显式失败转死信）；event_id 唯一约束兜底重复投递（M-12 与
 * stream 消费去重门双保险）；落库租户以事件 payload 为事实源（缺失归一保留值 system），
 * 经 TenantContext 绑定驱动 @TenantId 注入并在作用域结束恢复。
 *
 * <p>事务开启时机（business-api-integration G3 集成测试暴露的缺陷修复）：@TenantId 注入值与
 * UnboundTenantGuard 均以会话租户为准，而会话租户在事务开启时解析——故事务必须在租户绑定
 * 作用域内开启（编程式 TransactionTemplate），方法级 @Transactional 会在绑定前开会话导致
 * 注入取到调用方租户（或未绑定哨兵被拒）。等价性：事务覆盖 exists + save 单写库操作不变。</p>
 */
@Service
public class AuditIngestUseCase {

    /** 系统级事件（无租户语境）的租户归一保留值。 */
    public static final String SYSTEM_TENANT = "system";

    private static final Logger log = LoggerFactory.getLogger(AuditIngestUseCase.class);

    private final OpAuditEventRepository repository;
    private final TransactionTemplate transactionTemplate;

    public AuditIngestUseCase(OpAuditEventRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 消费落库（10.3.2 幂等：重复事件静默跳过）。 */
    public void ingest(AuditEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()
                || event.action() == null || event.action().isBlank() || event.occurredAt() == null) {
            throw new BizException(InfraError.AUDIT_EVENT_INVALID);
        }
        TenantContext previous = TenantContext.get();
        TenantContext.bind(new TenantContext(normalizeTenant(event.tenantId())));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (repository.existsByEventId(event.eventId())) {
                    return;
                }
                OpAuditEvent row = new OpAuditEvent(event.eventId(), event.traceId(), event.spanId(),
                        event.operatorId(), event.channel(), event.action(),
                        event.targetType(), event.targetId(), event.content(), event.occurredAt());
                repository.saveAndFlush(row);
            });
        } catch (DataIntegrityViolationException ex) {
            // event_id 唯一约束兜底（并发重复投递）：幂等跳过（M-12；saveAndFlush 即时暴露冲突，事务已回滚无脏行）
            log.info("审计事件重复投递，幂等跳过：eventId={}", event.eventId());
        } finally {
            TenantContext.bind(previous);
        }
    }

    /** 租户归一：payload 为空视为系统级事件。 */
    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? SYSTEM_TENANT : tenantId;
    }
}
