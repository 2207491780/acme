package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * 撤销集批量写入支撑（S14 收敛登录/扫码确认/切换互踢与自助改密/重置密码五处同款骨架）：
 * 逐个按剩余 TTL 写入撤销集，剩余有效期 ≤0 跳过（过期令牌网关 exp 校验兜底，无需撤销集）；
 * 失败策略二选一：
 * <ul>
 *   <li>{@link #bestEffort}：WARN 降级不阻断（互踢路径口径——旧令牌至多存活至过期）；</li>
 *   <li>{@link #allOrFail}：显式失败响应（改密/重置路径口径——底账已终态而撤销失败，
 *       不得「成功但令牌仍可用」，FR-004 异常分支）。</li>
 * </ul>
 * 写入顺序保持调用方清单序（失败即止语义与既有逐个循环一致）。
 */
final class SessionRevocations {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocations.class);

    private SessionRevocations() {
    }

    /** best-effort 批量写入（互踢路径）：写失败 WARN 降级，不阻断当前主流程。 */
    static void bestEffort(SessionRedis sessionRedis, Collection<SessionRecorder.RevocableToken> revocables,
                           Long userId, String scenario) {
        for (SessionRecorder.RevocableToken revocable : revocables) {
            long remaining = Duration.between(Instant.now(), revocable.expiresAt()).toSeconds();
            if (remaining <= 0) {
                continue;
            }
            try {
                sessionRedis.revoke(revocable.tokenId(), remaining);
            } catch (RuntimeException ex) {
                log.warn("{}撤销集写入失败（旧令牌至多存活至过期）：userId={} tokenId={}",
                        scenario, userId, revocable.tokenId(), ex);
            }
        }
    }

    /** 全量写入且任一失败即显式失败（改密/重置路径）：剩余有效期不足 1s 的自然过期跳过。 */
    static void allOrFail(SessionRedis sessionRedis, Collection<SessionRecorder.RevocableToken> revocables) {
        for (SessionRecorder.RevocableToken revocable : revocables) {
            long remaining = Duration.between(Instant.now(), revocable.expiresAt()).toSeconds();
            if (remaining <= 0) {
                continue;
            }
            try {
                sessionRedis.revoke(revocable.tokenId(), remaining);
            } catch (RuntimeException ex) {
                throw new BizException(CommonError.INTERNAL_ERROR);
            }
        }
    }
}
