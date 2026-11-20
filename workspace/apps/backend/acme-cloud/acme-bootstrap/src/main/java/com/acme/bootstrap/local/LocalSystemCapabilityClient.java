package com.acme.bootstrap.local;

import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemCapabilityClient;
import com.acme.platform.system.application.SystemCapabilityUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单体本地短路适配器（REQ-20261121-054613 确认⑤ 服务鉴权 SDK 单体形态）：
 * 单体本地短路，无远程调用；分布式由 Feign 提供（{@code @Primary} + Feign 惰性化解，
 * 同 {@link LocalTenantClient} 类注）。直调 system 域 {@link SystemCapabilityUseCase}，
 * 行为等价于 InternalCapabilityController 的 /internal/v1/system/capabilities/current 端点
 * （载荷逐字对齐：能力码 List&lt;String&gt;）。上下文口径：透明透传调用方 TenantContext
 * （能力消费方链路已绑租户上下文；缺租户 → 空数组 fail-closed，确认④）。
 */
@Component
@Primary
public class LocalSystemCapabilityClient implements SystemCapabilityClient {

    private final SystemCapabilityUseCase systemCapabilityUseCase;

    public LocalSystemCapabilityClient(SystemCapabilityUseCase systemCapabilityUseCase) {
        this.systemCapabilityUseCase = systemCapabilityUseCase;
    }

    /** 当前租户生效能力码（实时解析；绑定→套餐能力码 / 未绑定→全部启用 / 缺租户→空数组）。 */
    @Override
    public ContractResult<List<String>> currentCapabilities() {
        return LocalContractResults.ok(systemCapabilityUseCase.currentTenantCapabilityCodes());
    }
}
