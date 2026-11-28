package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.UsageOverviewView;
import com.acme.platform.system.application.UsageOverviewUseCase;
import com.acme.framework.web.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营总览接口（REQ-20261122-233426 确认⑥；与 TenantController/TenantQuotaController 同基路径、
 * 子路径互异——拆分 controller 保持既有构造兼容面不变；字面路径 usage-overview 优先于 /{tenantId}
 * 变量匹配，GET /tree 同款先例）。实时旁路聚合（确认⑥：数据量小先实时，无缓存与定时聚合）。
 */
@RestController
@RequestMapping("/admin/v1/system/tenants")
public class UsageOverviewController {

    private final UsageOverviewUseCase usageOverviewUseCase;

    public UsageOverviewController(UsageOverviewUseCase usageOverviewUseCase) {
        this.usageOverviewUseCase = usageOverviewUseCase;
    }

    /** 运营总览（totalTenants + 派生四视觉态计数 + 用量 TopN=20 按 storageBytes 降序）。 */
    @GetMapping("/usage-overview")
    @RequirePermission(SystemPermissions.TENANT_USAGE_OVERVIEW)
    public UsageOverviewView usageOverview() {
        return usageOverviewUseCase.overview();
    }
}
