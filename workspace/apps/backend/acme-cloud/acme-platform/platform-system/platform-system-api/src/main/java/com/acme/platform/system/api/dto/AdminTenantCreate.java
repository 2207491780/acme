package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 租户创建请求（tenantCode 全局唯一且不可改，为租户标识取值来源）。
 * REQ-20261117-025200 增补联系人 / 有效期三字段（均可空；expiresAt ISO-8601 字符串由 Jackson 反序列化，
 * null=长期有效；不校验过去时间——存量允许，登录时拒绝即可，契约 §API 租户 CRUD 扩展）。
 * REQ-20261121-022910 增补 packageId（可空=null 不绑定套餐；非空须为既有套餐，否则 TENANT_PACKAGE_NOT_FOUND）。
 * REQ-20261122-102954 增补 parentId（可空=null 顶层；非空须为既有租户，层级沿 parent 链校验，
 * 深度超限报 TENANT_DEPTH_EXCEEDED）。
 */
public record AdminTenantCreate(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String tenantCode,
                                @NotBlank @Size(min = 1, max = 128) String tenantName,
                                @Size(max = 255) String remark,
                                @Size(max = 64) String contactName,
                                @Size(max = 32) String contactPhone,
                                Instant expiresAt,
                                Long packageId,
                                Long parentId) {

    /** 兼容构造（REQ-20261122-102954 之前调用面）：parentId 缺省 null=顶层。 */
    public AdminTenantCreate(String tenantCode, String tenantName, String remark, String contactName,
                             String contactPhone, Instant expiresAt, Long packageId) {
        this(tenantCode, tenantName, remark, contactName, contactPhone, expiresAt, packageId, null);
    }
}
