package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 租户修改请求（tenantCode 不可改；version=乐观锁版本；无删除能力——停用代替，FR-007）。
 * REQ-20261117-025200 增补联系人 / 有效期三字段（均可空；expiresAt ISO-8601 字符串由 Jackson 反序列化，
 * null=长期有效；不校验过去时间——存量允许，登录时拒绝即可，契约 §API 租户 CRUD 扩展）。
 * REQ-20261121-022910 增补 packageId（null=解除套餐绑定（不受限）；非空须为既有套餐，否则
 * TENANT_PACKAGE_NOT_FOUND，契约 §API 租户更新扩展）。
 * REQ-20261122-102954 增补 parentId（可空=null 移至顶层；非空改父=沿 parent 链重校验环+深度，
 * 后代 level 整体重算，环报 TENANT_CYCLE / 深度超限报 TENANT_DEPTH_EXCEEDED）。
 * REQ-20261122-150802 增补 graceUntil（可空=null 清除宽限；确认⑥管理面新增动作）。
 */
public record AdminTenantUpdate(@NotBlank @Size(min = 1, max = 128) String tenantName,
                                @Size(max = 255) String remark,
                                @Size(max = 64) String contactName,
                                @Size(max = 32) String contactPhone,
                                Instant expiresAt,
                                Instant graceUntil,
                                @NotNull Long version,
                                Long packageId,
                                Long parentId) {

    /** 兼容构造（REQ-20261122-102954 之前调用面）：graceUntil/parentId 缺省 null。 */
    public AdminTenantUpdate(String tenantName, String remark, String contactName, String contactPhone,
                             Instant expiresAt, Long version, Long packageId) {
        this(tenantName, remark, contactName, contactPhone, expiresAt, null, version, packageId, null);
    }
}
