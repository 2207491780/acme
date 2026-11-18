package com.acme.platform.auth.application;

import com.acme.platform.auth.api.TenantSessionRevokeView;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 租户会话批量撤销用例（REQ-20261117-025200 确认记录①②）：platform-system 停用租户事务提交后
 * 经撤销契约调用（internal 端点 /internal/v1/tenant-sessions/revocations）。
 * 编排口径：底账 KICKED 收敛事务（SessionRecorder）→ 撤销集逐个事务外写入（M-05 事务内无远程面）；
 * 单个撤销集写入失败计 failedCount 并 WARN 继续（降级不回滚底账，DB 为事实源，
 * 残留会话至多存活至 access TTL 30min，与互踢撤销失败降级同口径）；未知租户（无活跃会话）
 * 幂等成功返回 0/0。
 */
@Service
public class TenantSessionRevokeUseCase {

    private static final Logger log = LoggerFactory.getLogger(TenantSessionRevokeUseCase.class);

    private final SessionRecorder sessionRecorder;
    private final SessionRedis sessionRedis;

    public TenantSessionRevokeUseCase(SessionRecorder sessionRecorder, SessionRedis sessionRedis) {
        this.sessionRecorder = sessionRecorder;
        this.sessionRedis = sessionRedis;
    }

    /**
     * 按租户代码批量撤销活跃会话（底账 KICKED 事务 + 撤销集事务外逐个写入）。
     *
     * @param tenantCode 目标租户代码（auth_session.tenant_id 同口径存租户代码）
     * @return 撤销 / 失败计数（未知租户为 0/0）
     */
    public TenantSessionRevokeView revokeByTenant(String tenantCode) {
        List<SessionRecorder.RevocableToken> targets = sessionRecorder.kickTenantActiveSessions(tenantCode);
        int revoked = 0;
        int failed = 0;
        for (SessionRecorder.RevocableToken target : targets) {
            long remaining = Duration.between(Instant.now(), target.expiresAt()).toSeconds();
            if (remaining <= 0) {
                // 查询面已过滤未过期（防御式双查）：过期令牌网关 exp 校验兜底，无需撤销集
                continue;
            }
            try {
                sessionRedis.revoke(target.tokenId(), remaining);
                revoked++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("租户停用撤销集写入失败（降级继续，残留会话至多存活至过期）：tenantCode={} tokenId={}",
                        tenantCode, target.tokenId(), ex);
            }
        }
        return new TenantSessionRevokeView(revoked, failed);
    }
}
