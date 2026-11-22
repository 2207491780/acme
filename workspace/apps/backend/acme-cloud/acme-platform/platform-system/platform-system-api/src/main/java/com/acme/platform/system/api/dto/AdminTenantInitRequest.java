package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 租户开通初始化请求（tenant-management-complete）：建租户 + 租户管理员 + 默认授权一步完成。
 * 管理员初始密码不经本接口（凭据归 platform-auth，M-02 无 system→auth 依赖）：
 * 开通后由具备 auth:credential:manage 权限的操作者经
 * {@code PUT /admin/v1/auth/credentials/users/{adminUserId}/password} 设置。
 * REQ-20261122-102954 增补 parentId（可空=null 顶层；平台侧建子租户，初始管理员归子租户编码，
 * level=父+1，深度超限报 TENANT_DEPTH_EXCEEDED）。
 */
public record AdminTenantInitRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String tenantCode,
        @NotBlank @Size(min = 1, max = 128) String tenantName,
        @Size(max = 255) String remark,
        @NotBlank @Size(min = 3, max = 64) String adminUsername,
        @NotBlank @Size(min = 1, max = 64) String adminNickname,
        Long parentId) {

    /** 兼容构造（REQ-20261122-102954 之前调用面）：parentId 缺省 null=顶层。 */
    public AdminTenantInitRequest(String tenantCode, String tenantName, String remark,
                                  String adminUsername, String adminNickname) {
        this(tenantCode, tenantName, remark, adminUsername, adminNickname, null);
    }
}
