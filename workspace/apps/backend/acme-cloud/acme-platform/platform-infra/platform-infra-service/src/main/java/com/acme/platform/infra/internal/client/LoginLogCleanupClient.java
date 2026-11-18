package com.acme.platform.infra.internal.client;

import com.acme.platform.system.api.ContractResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 登录日志保留期清理 Feign 声明（REQ-20261114-120105 TL 裁决 C 方案；TL 记录的实现机制增补）：
 * platform-infra 任务体调用 platform-auth 内部清理端点（幂等可重复调用）。
 * 声明置于消费方 infra 侧——platform-auth-api 为薄契约且 enforcer 禁 openfeign-core（9.1.2
 * 本期无服务间消费方），信封解码复用 platform-system-api 的 {@link ContractResult}。
 * 错误响应（非 2xx）经 framework-feign 错误解码为 BizException。
 */
@FeignClient(name = "platform-auth", contextId = "loginLogCleanupClient")
public interface LoginLogCleanupClient {

    /** 软删标记 + 物理清理（幂等）；成功信封 data=本次物理清理条数。 */
    @PostMapping("/internal/v1/login-logs/purge-cleanup")
    ContractResult<Integer> purgeCleanup();
}
