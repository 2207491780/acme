package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 租户开通初始化请求（tenant-management-complete）：建租户 + 租户管理员 + 默认授权一步完成。
 * 管理员初始密码不经本接口（凭据归 platform-auth，M-02 无 system→auth 依赖）：
 * 开通后由具备 auth:credential:manage 权限的操作者经
 * {@code PUT /admin/v1/auth/credentials/users/{adminUserId}/password} 设置。
 */
public record AdminTenantInitRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String tenantCode,
        @NotBlank @Size(min = 1, max = 128) String tenantName,
        @Size(max = 255) String remark,
        @NotBlank @Size(min = 3, max = 64) String adminUsername,
        @NotBlank @Size(min = 1, max = 64) String adminNickname) {
}
