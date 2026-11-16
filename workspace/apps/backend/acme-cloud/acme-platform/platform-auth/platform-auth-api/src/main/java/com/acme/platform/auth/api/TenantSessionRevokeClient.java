package com.acme.platform.auth.api;

import com.acme.platform.system.api.ContractResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 租户会话批量撤销契约（REQ-20261117-025200 确认记录②）：platform-auth 为提供方（实现于
 * platform-auth-service InternalTenantSessionController），消费方 platform-system 停用租户后
 * 事务提交后调用（枚举撤销，afterCommit）；internal 端点不经网关、openapi 不登记。
 * 错误响应（非 2xx）经 framework-feign 错误解码为 BizException，不经本类型。
 */
@FeignClient(name = "platform-auth", contextId = "tenantSessionRevokeClient")
public interface TenantSessionRevokeClient {

    /**
     * 按租户代码批量撤销活跃会话：底账置 KICKED（提供方事务）→ 逐个撤销集写入（失败继续计数）。
     * 未知租户（无该租户会话）幂等成功返回 0/0（契约 §API）。
     *
     * @param request 撤销请求（tenantCode 必填）
     * @return 信封携带撤销 / 失败计数
     */
    @PostMapping("/internal/v1/tenant-sessions/revocations")
    ContractResult<TenantSessionRevokeView> revokeByTenant(TenantSessionRevokeRequest request);
}
