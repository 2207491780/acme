package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.api.TenantInternalView;
import com.acme.platform.system.api.TenantQuotaView;
import com.acme.platform.system.api.TenantSummary;
import com.acme.platform.system.application.TenantQuotaUseCase;
import com.acme.platform.system.application.TenantUseCase;
import com.acme.platform.system.domain.SysTenant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户目录内部接口（login-tenant-select / REQ-20261117-025200；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1），供 platform-auth 登录页租户下拉聚合
 * 与租户 by-code 校验（登录过期校验 / 超管切换目标校验共用契约，确认记录③④）。
 * REQ-20261122-150802 增补配额解析端点（platform-infra 文件上传配额校验数据源，确认③）。
 */
@RestController
public class InternalTenantController {

    private final TenantUseCase tenantUseCase;
    private final TenantQuotaUseCase tenantQuotaUseCase;

    @Autowired
    public InternalTenantController(TenantUseCase tenantUseCase, TenantQuotaUseCase tenantQuotaUseCase) {
        this.tenantUseCase = tenantUseCase;
        this.tenantQuotaUseCase = tenantQuotaUseCase;
    }

    /**
     * 兼容构造（REQ-20261122-150802 之前直构调用面）：配额解析端点缺席——本构造实例不提供
     * /internal/v1/tenants/quota（调用即失败），生产装配恒走双参构造。
     */
    public InternalTenantController(TenantUseCase tenantUseCase) {
        this(tenantUseCase, null);
    }

    /** 启用租户清单（登录下拉数据源；平台级跨租户查询由用例 @IgnoreTenant 声明；宽限内租户保留确认②）。 */
    @GetMapping("/internal/v1/tenants")
    public Result<List<TenantSummary>> listEnabled() {
        return Result.ok(tenantUseCase.listEnabled().stream()
                .map(t -> new TenantSummary(t.getTenantCode(), t.getTenantName()))
                .toList());
    }

    /**
     * 按租户代码查租户详情（REQ-20261117-025200，确认记录③④）：
     * 未知租户 → TENANT_NOT_FOUND 错误信封（404，消费方经 framework-feign 错误解码捕获）。
     * REQ-20261122-150802：下发 graceUntil（登录闸门派生输入，确认②）。
     */
    @GetMapping("/internal/v1/tenants/by-code")
    public Result<TenantInternalView> byCode(@RequestParam("tenantCode") String tenantCode) {
        SysTenant tenant = tenantUseCase.findByCode(tenantCode);
        return Result.ok(new TenantInternalView(tenant.getTenantCode(), tenant.getTenantName(),
                tenant.getStatus().name(), tenant.getExpiresAt(), tenant.getGraceUntil()));
    }

    /**
     * 按租户代码解析有效存储配额（REQ-20261122-150802 确认③三级覆盖链）：
     * 未知租户 → TENANT_NOT_FOUND 错误信封（404，消费方 FileUseCase 回落平台缺省）。
     */
    @GetMapping("/internal/v1/tenants/quota")
    public Result<TenantQuotaView> quota(@RequestParam("tenantCode") String tenantCode) {
        return Result.ok(tenantQuotaUseCase.resolveQuota(tenantCode));
    }
}
