package com.acme.platform.infra.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.infra.api.ActionCount;
import com.acme.platform.infra.application.AuditStatsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计统计内部接口（REQ-20261119-011524 确认记录②；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1），消费方 platform-system 首页概览聚合。
 * 免权限点（确认记录④：统计为平台概览数字面，服务身份调用无越权面）。
 */
@RestController
public class InternalAuditStatsController {

    private final AuditStatsUseCase auditStatsUseCase;

    public InternalAuditStatsController(AuditStatsUseCase auditStatsUseCase) {
        this.auditStatsUseCase = auditStatsUseCase;
    }

    /**
     * 近 N 日审计动作分布 top M（按条数降序；无事件返回空列表，消费方按空态降级显示）。
     *
     * @param days 统计天数窗口（缺省 30，确认记录①）
     * @param top  截断条数（缺省 5，确认记录①）
     */
    @GetMapping("/internal/v1/audit-stats/distribution")
    public Result<List<ActionCount>> actionDistribution(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "5") int top) {
        return Result.ok(auditStatsUseCase.actionDistribution(days, top));
    }
}
