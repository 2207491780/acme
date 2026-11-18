package com.acme.platform.infra.application;

import com.acme.common.core.BizException;
import com.acme.platform.infra.internal.client.LoginLogCleanupClient;
import com.acme.platform.system.api.ContractResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 登录日志保留期清理用例（REQ-20261114-120105，TL 裁决 C 方案）：infra 仅注册任务并触发——
 * 任务体经 Feign 调 platform-auth 内部清理端点（POST /internal/v1/login-logs/purge-cleanup），
 * 表访问与物理删除逻辑收敛 platform-auth（schema 独占口径不破）。
 * 返回「本次物理清理条数」；调用失败向上抛出（任务终态 FAILED，FR-028 执行记录口径）。
 * 显式 bean 名（REQ-20261118-002130）：与 auth 域同名类在单体裁装下消歧，纯命名注解无行为变化。
 */
@Service("infraLoginLogRetentionCleanupUseCase")
public class LoginLogRetentionCleanupUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginLogRetentionCleanupUseCase.class);

    private final LoginLogCleanupClient cleanupClient;

    public LoginLogRetentionCleanupUseCase(LoginLogCleanupClient cleanupClient) {
        this.cleanupClient = cleanupClient;
    }

    /** 触发 platform-auth 清理（幂等）；返回本次物理清理条数。 */
    public int cleanup() {
        ContractResult<Integer> result = cleanupClient.purgeCleanup();
        Integer purged = result.payload();
        log.info("登录日志保留期清理触发完成：purged={}", purged);
        return purged == null ? 0 : purged;
    }
}
