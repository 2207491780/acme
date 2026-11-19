package com.acme.platform.auth.api;

import com.acme.platform.system.api.ContractResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 登录日志按日统计契约（REQ-20261119-011524 确认记录②③）：platform-auth 为提供方（实现于
 * platform-auth-service InternalStatsController），消费方 platform-system 首页概览聚合调用
 * （sys_login_log 按日聚合，channel 不分；服务身份免权限点，internal 端点不经网关、openapi 不登记）。
 * 错误响应（非 2xx）经 framework-feign 错误解码为 BizException，不经本类型；
 * 消费方降级口径（0/空 + WARN）由 DashboardUseCase 承担。
 */
@FeignClient(name = "platform-auth", contextId = "loginLogStatsClient")
public interface LoginLogStatsClient {

    /**
     * 近 N 日登录日志按日统计（无日志日不出行——消费方按需补零）。
     *
     * @param days 统计天数窗口（自当前日起往前 days 天；缺省 14，由消费方显式传参）
     * @return 信封携带按日升序统计点列表（date=YYYY-MM-DD）
     */
    @GetMapping("/internal/v1/login-log-stats")
    ContractResult<List<LoginLogStatsPoint>> dailyStats(@RequestParam("days") int days);
}
