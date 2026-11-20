package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 服务能力解析契约（REQ-20261121-054613 确认⑤ 服务鉴权 SDK）：能力消费方
 * （platform-workflow / platform-scheduler，后续轮次建设）解析当前租户生效能力码，
 * 以服务身份主体调用（Q-D08，同 TenantClient 口径）；internal 面不经网关（11.6.1 内部令牌校验）。
 * TenantContext 经 ContextHeaderRequestInterceptor 随调用透传：
 * 绑定套餐 → 套餐能力码；未绑定套餐 → 全部启用能力码；缺租户 → 空数组 fail-closed
 * （服务调用方自行决定缺省语义）。
 */
@FeignClient(name = "platform-system", contextId = "systemCapabilityClient")
public interface SystemCapabilityClient {

    /** 当前租户生效能力码（实时解析，套餐增删能力即时生效，确认④ AC-5）。 */
    @GetMapping("/internal/v1/system/capabilities/current")
    ContractResult<List<String>> currentCapabilities();
}
