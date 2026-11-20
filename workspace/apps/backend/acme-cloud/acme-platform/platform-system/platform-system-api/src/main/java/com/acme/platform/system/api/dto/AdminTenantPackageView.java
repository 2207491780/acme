package com.acme.platform.system.api.dto;

import java.time.Instant;

/**
 * 套餐视图（REQ-20261121-022910 分页 / 创建 / 修改响应）：
 * menuCount=套餐菜单绑定数；tenantCount=绑定该套餐的租户数（实时计数，确认②读时口径）。
 *
 * @param id          套餐 ID（wire 字符串化雪花 ID）
 * @param packageName 套餐名称（全局唯一）
 * @param remark      备注（可空）
 * @param menuCount   套餐菜单绑定数
 * @param tenantCount 绑定该套餐的租户数
 * @param createdAt   创建时间
 * @param version     乐观锁版本（wire 字符串化）
 */
public record AdminTenantPackageView(Long id, String packageName, String remark,
                                     Long menuCount, Long tenantCount,
                                     Instant createdAt, Long version) {
}
