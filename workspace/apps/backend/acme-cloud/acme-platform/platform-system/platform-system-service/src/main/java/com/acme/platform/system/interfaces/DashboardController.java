package com.acme.platform.system.interfaces;

import com.acme.common.context.UserContext;
import com.acme.common.core.Result;
import com.acme.platform.system.api.dto.DashboardSummaryView;
import com.acme.platform.system.application.DashboardUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页平台概览接口（REQ-20261119-011524 确认记录①④：路径与契约 §API 逐字一致）。
 * 认证即可（无权限点——统计为平台概览数字非敏感面，入口型 widget 显隐由前端按各域 list 权限判定）；
 * unread 以当前主体为收件人语义（UserContext.userId，与 /admin/v1/system/messages/unread-count 同径）。
 */
@RestController
@RequestMapping("/admin/v1/system/dashboard")
public class DashboardController {

    private final DashboardUseCase dashboardUseCase;

    public DashboardController(DashboardUseCase dashboardUseCase) {
        this.dashboardUseCase = dashboardUseCase;
    }

    /** 平台概览聚合（60s 静态缓存 + unread 实时；下游降级 0/空语义见 DashboardSummaryView）。 */
    @GetMapping("/summary")
    public Result<DashboardSummaryView> summary() {
        return Result.ok(dashboardUseCase.summary(currentRecipientId()));
    }

    /** 当前主体（String userId → Long；解析失败归 0=系统主体，未读恒 0 fail-closed）。 */
    private static Long currentRecipientId() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            return 0L;
        }
        try {
            return Long.valueOf(context.userId());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
