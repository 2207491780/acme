package com.acme.platform.auth.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.auth.api.LoginLogStatsPoint;
import com.acme.platform.auth.application.LoginLogQueryUseCase;
import com.acme.platform.auth.application.OnlineSessionUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计内部接口（REQ-20261119-011524 确认记录②；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1），消费方 platform-system 首页概览聚合。
 * 免权限点（确认记录④：统计为平台概览数字面，服务身份调用无越权面）。
 */
@RestController
public class InternalStatsController {

    private final OnlineSessionUseCase onlineSessionUseCase;
    private final LoginLogQueryUseCase loginLogQueryUseCase;

    public InternalStatsController(OnlineSessionUseCase onlineSessionUseCase,
                                   LoginLogQueryUseCase loginLogQueryUseCase) {
        this.onlineSessionUseCase = onlineSessionUseCase;
        this.loginLogQueryUseCase = loginLogQueryUseCase;
    }

    /** 当前活跃会话数（ACTIVE 且未过期；全平台口径不过滤租户，Q-D04）。 */
    @GetMapping("/internal/v1/online-count")
    public Result<Long> onlineCount() {
        return Result.ok(onlineSessionUseCase.countActive());
    }

    /**
     * 近 N 日登录日志按日统计（channel 不分；无日志日不出行，消费方按需补零）。
     *
     * @param days 统计天数窗口（缺省 14，确认记录①）
     */
    @GetMapping("/internal/v1/login-log-stats")
    public Result<List<LoginLogStatsPoint>> loginLogStats(
            @RequestParam(defaultValue = "14") int days) {
        return Result.ok(loginLogQueryUseCase.dailyStats(days));
    }
}
