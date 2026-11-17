package com.acme.platform.infra.application;

import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.platform.infra.config.ExceptionLogRetentionProperties;
import com.acme.platform.infra.domain.OpException;
import com.acme.platform.infra.infrastructure.repository.OpExceptionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 运行异常日志保留期清理用例（REQ-20261117-061017 确认②）：
 * 清理软删超期（{@code acme.exception-log.retention-days}，按 updated_at 判定）的 sys_op_exception 行
 * （硬删，未超期/未软删行不受影响，先查后删复用 FileRetentionCleanupUseCase 手法）。
 * 平台级跨租户语义（调度无租户语境）：经 {@code @IgnoreTenant} 显式声明旁路（12.4.2 平台管理场景）。
 */
@Service
public class OpExceptionRetentionCleanupUseCase {

    private final OpExceptionRepository repository;
    private final ExceptionLogRetentionProperties properties;

    public OpExceptionRetentionCleanupUseCase(OpExceptionRepository repository,
                                              ExceptionLogRetentionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 清理软删超期记录（返回清理条数）。 */
    @IgnoreTenant("infra.exception-log-retention-cleanup")
    public int cleanupExpired() {
        Instant cutoff = Instant.now()
                .minus(Duration.ofDays(properties.getRetentionDays()));
        List<OpException> expired = repository.findSoftDeletedExpiredBefore(cutoff);
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> removedIds = expired.stream().map(OpException::getId).toList();
        repository.forceDeleteByIds(removedIds);
        return removedIds.size();
    }
}
