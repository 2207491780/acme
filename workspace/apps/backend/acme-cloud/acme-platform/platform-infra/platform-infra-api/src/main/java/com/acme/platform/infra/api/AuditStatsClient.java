package com.acme.platform.infra.api;

import com.acme.platform.system.api.ContractResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 审计动作分布统计契约（REQ-20261119-011524 确认记录②③）：platform-infra 为提供方（实现于
 * platform-infra-service InternalAuditStatsController），消费方 platform-system 首页概览聚合调用
 * （acme_op_audit action 分布 top N，跨租户平台口径 @IgnoreTenant；服务身份免权限点，
 * internal 端点不经网关、openapi 不登记）。错误响应（非 2xx）经 framework-feign 错误解码为
 * BizException，不经本类型；消费方降级口径（0/空 + WARN）由 DashboardUseCase 承担。
 */
@FeignClient(name = "platform-infra", contextId = "auditStatsClient")
public interface AuditStatsClient {

    /**
     * 近 N 日审计动作分布 top M（按条数降序；无事件返回空列表）。
     *
     * @param days 统计天数窗口（自当前日往前 days 天；缺省 30，由消费方显式传参）
     * @param top  截断条数（缺省 5；&lt;1 由提供方按 1 处理）
     * @return 信封携带动作分布统计项列表（按条数降序）
     */
    @GetMapping("/internal/v1/audit-stats/distribution")
    ContractResult<List<ActionCount>> actionDistribution(@RequestParam("days") int days,
                                                         @RequestParam("top") int top);
}
