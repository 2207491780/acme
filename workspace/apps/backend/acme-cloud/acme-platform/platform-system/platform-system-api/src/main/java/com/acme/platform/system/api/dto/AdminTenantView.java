package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;
import java.util.List;

/**
 * 租户视图（跨租户平台管理场景，12.4.2）。
 * REQ-20261117-025200 增补联系人 / 有效期三字段（可空语义：null=未配置 / 长期有效）。
 * REQ-20261122-102954 增补层级字段（parentId 可空=null 顶层；level 1=顶层，上限 3，确认②⑨），
 * children 供 tree 端点嵌套返回（平铺 list/detail 响应恒为空列表，与 AdminDeptView 同款）。
 *
 * @param id           租户 ID（wire 字符串化雪花 ID）
 * @param tenantCode   租户代码（全局唯一，不可改）
 * @param tenantName   租户名称
 * @param status       启停状态
 * @param remark       备注（可空）
 * @param contactName  联系人姓名（可空；REQ-20261117-025200）
 * @param contactPhone 联系人电话（可空；REQ-20261117-025200）
 * @param expiresAt    租户有效期（可空=长期；过期租户登录拒绝与下拉排除，确认记录③；ISO-8601 字符串）
 * @param createdAt    创建时间
 * @param version      乐观锁版本（wire 字符串化）
 * @param packageId    绑定套餐 ID（可空=null 不受套餐限制；REQ-20261121-022910 确认⑦租户管理页套餐列）
 * @param parentId     父租户 ID（可空=null 顶层；REQ-20261122-102954 确认⑨）
 * @param level        层级（1=顶层，上限 3；REQ-20261122-102954 确认②）
 * @param children     子租户（tree 端点嵌套；平铺响应恒空列表）
 */
public record AdminTenantView(Long id, String tenantCode, String tenantName, EnableStatus status,
                              String remark, String contactName, String contactPhone, Instant expiresAt,
                              Instant createdAt, Long version, Long packageId,
                              Long parentId, Integer level, List<AdminTenantView> children) {
}
