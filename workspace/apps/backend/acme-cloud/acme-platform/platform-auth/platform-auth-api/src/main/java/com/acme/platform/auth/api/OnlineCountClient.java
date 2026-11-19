package com.acme.platform.auth.api;

import com.acme.platform.system.api.ContractResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 在线数统计契约（REQ-20261119-011524 确认记录②③）：platform-auth 为提供方（实现于
 * platform-auth-service InternalStatsController），消费方 platform-system 首页概览聚合调用
 * （auth_session ACTIVE 且未过期计数；服务身份免权限点，internal 端点不经网关、openapi 不登记）。
 * 错误响应（非 2xx）经 framework-feign 错误解码为 BizException，不经本类型；
 * 消费方降级口径（0/空 + WARN）由 DashboardUseCase 承担。
 */
@FeignClient(name = "platform-auth", contextId = "onlineCountClient")
public interface OnlineCountClient {

    /**
     * 当前活跃会话数（ACTIVE 且未过期；全平台口径，不过滤租户）。
     *
     * @return 信封携带在线数（裸数字，data 字段经 LongToStringModule wire 为字符串）
     */
    @GetMapping("/internal/v1/online-count")
    ContractResult<Long> onlineCount();
}
