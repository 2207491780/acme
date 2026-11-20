package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.application.SystemCapabilityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务能力内部接口（REQ-20261121-054613 确认④⑤ 服务鉴权 SDK 服务端面；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1），无权限点标注（internal 面惯例），
 * 供能力消费方（platform-workflow / platform-scheduler，后续轮次建设）经 SystemCapabilityClient
 * Feign 契约解析当前租户生效能力码；bootstrap 单体形态由 LocalSystemCapabilityClient 直调用例短路。
 */
@RestController
public class InternalCapabilityController {

    private final SystemCapabilityUseCase systemCapabilityUseCase;

    public InternalCapabilityController(SystemCapabilityUseCase systemCapabilityUseCase) {
        this.systemCapabilityUseCase = systemCapabilityUseCase;
    }

    /**
     * 当前租户生效能力码（TenantContext 驱动，确认④）：绑定套餐 → 套餐能力码；未绑定套餐 →
     * 全部启用能力码；缺租户（TenantContext 缺失 / 租户行缺失）→ 空数组 fail-closed
     * （服务调用方自行决定缺省语义）。读时实时解析，套餐增删能力即时生效（AC-5）。
     */
    @GetMapping("/internal/v1/system/capabilities/current")
    public Result<List<String>> current() {
        return Result.ok(systemCapabilityUseCase.currentTenantCapabilityCodes());
    }
}
