package com.acme.platform.infra.application;

import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.platform.infra.config.AuditRetentionProperties;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 审计事件保留期清理用例（REQ-20261117-061017 确认②）：
 * 清理软删超期（{@code acme.audit.retention-days}，按 updated_at 判定）的 acme_op_audit 行
 * （硬删，未超期/未软删行不受影响，先查后删复用 FileRetentionCleanupUseCase 手法）。
 * 平台级跨租户语义（调度无租户语境）：经 {@code @IgnoreTenant} 显式声明旁路（12.4.2 平台管理场景）。
 */
@Service
public class AuditRetentionCleanupUseCase {

    private final OpAuditEventRepository repository;
    private final AuditRetentionProperties properties;

    public AuditRetentionCleanupUseCase(OpAuditEventRepository repository,
                                        AuditRetentionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 清理软删超期记录（返回清理条数）。 */
    @IgnoreTenant("infra.audit-retention-cleanup")
    public int cleanupExpired() {
        Instant cutoff = Instant.now()
                .minus(Duration.ofDays(properties.getRetentionDays()));
        List<OpAuditEvent> expired = repository.findSoftDeletedExpiredBefore(cutoff);
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> removedIds = expired.stream().map(OpAuditEvent::getId).toList();
        repository.forceDeleteByIds(removedIds);
        return removedIds.size();
    }
}
