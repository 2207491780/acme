package com.acme.platform.system.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 套餐详情视图（REQ-20261121-022910：AdminTenantPackageView 形态 + menuIds[]；
 * capabilities 扩展 REQ-20261121-054613 确认④）：
 * menuIds=套餐菜单绑定全集（含 BUTTON 节点，与绑定表行一一对应，角色授权树勾选回显用）；
 * capabilities=套餐能力码全集（目录启用集内绑定，能力 checkbox 组编辑回显用）。
 */
public record AdminTenantPackageDetailView(Long id, String packageName, String remark,
                                           Long menuCount, Long tenantCount,
                                           Instant createdAt, Long version,
                                           List<Long> menuIds,
                                           List<String> capabilities) {
}
