package com.acme.platform.auth.application;

import com.acme.platform.auth.config.LoginLogRetentionProperties;
import com.acme.platform.auth.infrastructure.repository.LoginLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 登录日志保留期清理用例（REQ-20261114-120105，TL 裁决 C 方案：表访问与物理删除收敛
 * platform-auth，schema 独占口径不破；调度入口与任务注册在 platform-infra，经内部端点调用本用例）：
 * 物理删除软删（deleted=true）且 created_at 早于 {@code acme.login-log.retention-days}（缺省 30，
 * ≥2 天口径）的行；未软删行与未超期软删行不受影响（tester 400d/1d 双档口径）。
 * 幂等可重复调用，返回「本次物理清理条数」。「仅插入不改」=业务路径不改写日志，
 * deleted 软删列为保留期例外口径预留（确认记录⑦）。
 */
@Service
public class LoginLogRetentionCleanupUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginLogRetentionCleanupUseCase.class);

    private final LoginLogRepository loginLogRepository;
    private final LoginLogRetentionProperties properties;

    public LoginLogRetentionCleanupUseCase(LoginLogRepository loginLogRepository,
                                           LoginLogRetentionProperties properties) {
        this.loginLogRepository = loginLogRepository;
        this.properties = properties;
    }

    /**
     * 清理软删超期登录日志（幂等）。
     * 认证域无租户过滤装配（sys_login_log 平台级表，Q-D04 同型），无租户旁路需求。
     *
     * @return 本次物理清理条数
     */
    public int cleanupExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.getRetentionDays()));
        int purged = loginLogRepository.forceDeleteSoftDeletedExpiredBefore(cutoff);
        log.info("登录日志保留期清理完成：cutoff={} purged={}", cutoff, purged);
        return purged;
    }
}
