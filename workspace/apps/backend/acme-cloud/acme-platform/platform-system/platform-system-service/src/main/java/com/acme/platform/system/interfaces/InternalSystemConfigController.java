package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.application.SystemConfigUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统配置内部接口（REQ-20261117-011514 captcha-policy；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1），供 platform-auth 登录验证码策略判定
 * 与公开策略查询聚合。
 */
@RestController
public class InternalSystemConfigController {

    private final SystemConfigUseCase systemConfigUseCase;

    public InternalSystemConfigController(SystemConfigUseCase systemConfigUseCase) {
        this.systemConfigUseCase = systemConfigUseCase;
    }

    /** 租户验证码策略（tenantId 传租户代码；无配置行返回缺省 false，跨租户查询由用例 @IgnoreTenant 声明）。 */
    @GetMapping("/internal/v1/system-configs/captcha-policy")
    public Result<CaptchaPolicyView> captchaPolicy(@RequestParam("tenantId") String tenantId) {
        return Result.ok(systemConfigUseCase.findCaptchaPolicyByTenant(tenantId));
    }
}
