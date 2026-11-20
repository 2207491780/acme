package com.acme.platform.system.application;

import com.acme.common.context.TenantContext;
import com.acme.platform.system.domain.SysCapability;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.domain.SysTenantPackageCapability;
import com.acme.platform.system.infrastructure.repository.SysCapabilityRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageCapabilityRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 服务能力用例（REQ-20261121-054613 确认③④）：启用目录读取 + 当前租户生效能力解析（服务鉴权 SDK 支撑面）。
 * 生效口径（确认④ / TL 口径）：绑定套餐 → 套餐能力码；未绑定套餐（package_id NULL）→ 全部启用能力码；
 * 缺租户（TenantContext 缺失 / 租户行缺失）→ 空数组 fail-closed（服务调用方自行决定缺省语义）。
 * 读时实时解析（无物化缓存），套餐增删能力即时生效（确认④ AC-5）；全链路仅认 status=ENABLED
 * （目录停用即全局失效，fail-closed 同一口径）。
 *
 * <p>与 {@link TenantPackageUseCase#currentTenantPackageId()} 的差异：后者把"租户行缺失"并入
 * null=不受限（菜单下发既有基线）；本用例对租户行缺失按异常上下文 fail-closed 返回空集——
 * 能力面是服务间鉴权语义，宁可拒绝不误放行。</p>
 */
@Service
public class SystemCapabilityUseCase {

    private final SysTenantRepository tenantRepository;
    private final SysCapabilityRepository capabilityRepository;
    private final SysTenantPackageCapabilityRepository packageCapabilityRepository;

    public SystemCapabilityUseCase(SysTenantRepository tenantRepository,
                                   SysCapabilityRepository capabilityRepository,
                                   SysTenantPackageCapabilityRepository packageCapabilityRepository) {
        this.tenantRepository = tenantRepository;
        this.capabilityRepository = capabilityRepository;
        this.packageCapabilityRepository = packageCapabilityRepository;
    }

    /** 全量启用能力目录（id asc，确认④；仅 status=ENABLED）。 */
    public List<SysCapability> listEnabled() {
        return capabilityRepository.findEnabled();
    }

    /**
     * 当前租户生效能力码（TenantContext 驱动，确认④ / AC-5/AC-6）：
     * 绑定套餐 → 套餐能力码（启用集内）；未绑定套餐（package_id NULL）→ 全部启用能力码；
     * TenantContext 缺失 / 租户行缺失 → 空数组 fail-closed。
     */
    public List<String> currentTenantCapabilityCodes() {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            return List.of();
        }
        SysTenant tenant = tenantRepository.findByTenantCode(context.tenantId().trim()).orElse(null);
        if (tenant == null) {
            return List.of();
        }
        if (tenant.getPackageId() == null) {
            return enabledCapabilityCodes();
        }
        return packageCapabilityCodes(tenant.getPackageId());
    }

    /** 当前租户绑定套餐的能力码（启用集内；findEnabledByIdIn 已按 id asc 稳定输出）。 */
    private List<String> packageCapabilityCodes(Long packageId) {
        List<Long> capabilityIds = packageCapabilityRepository.findByPackageId(packageId).stream()
                .map(SysTenantPackageCapability::getCapabilityId)
                .toList();
        if (capabilityIds.isEmpty()) {
            return List.of();
        }
        return capabilityRepository.findEnabledByIdIn(capabilityIds).stream()
                .map(SysCapability::getCapabilityCode)
                .toList();
    }

    private List<String> enabledCapabilityCodes() {
        return capabilityRepository.findEnabled().stream()
                .map(SysCapability::getCapabilityCode)
                .toList();
    }
}
