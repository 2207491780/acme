package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 租户系统配置查询契约（REQ-20261117-011514 captcha-policy / REQ-20261117-025200 页脚版权）：
 * platform-auth 登录验证码策略判定与公开站点配置数据源，以服务身份主体调用
 * （Q-D08，同 TenantClient 口径）；tenantId 传租户代码（sys_config.tenant_id 存租户代码）。
 */
@FeignClient(name = "platform-system", contextId = "systemConfigClient")
public interface SystemConfigClient {

    /** 租户验证码策略（无配置行返回缺省关闭；平台级跨租户查询由服务侧用例 @IgnoreTenant 声明）。 */
    @GetMapping("/internal/v1/system-configs/captcha-policy")
    ContractResult<CaptchaPolicyView> findCaptchaPolicy(@RequestParam("tenantId") String tenantId);

    /** 租户公开站点配置（REQ-20261117-025200 页脚两字段；无配置行返回缺省 null/false，确认记录⑤）。 */
    @GetMapping("/internal/v1/system-configs/public-view")
    ContractResult<TenantPublicInternalView> findPublicView(@RequestParam("tenantId") String tenantId);
}
