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
 * REQ-20261122-233426 增补 templateCode（可选开通模板）与 adminUsername/adminNickname 放宽可空
 * （确认③：命中模板时缺省值经模板兜底——adminUsername=模板前缀+租户编码、adminNickname=模板昵称；
 * 未传模板或未命中模板时仍必填，缺失在用例层以 VALIDATION_FAILED 400 收敛，与原 bean 校验同码同态）；
 * 未传/未命中模板=现行为逐字不变（兼容零回归）。
 */
public record AdminTenantInitRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String tenantCode,
        @NotBlank @Size(min = 1, max = 128) String tenantName,
        @Size(max = 255) String remark,
        @Size(min = 3, max = 64) String adminUsername,
        @Size(min = 1, max = 64) String adminNickname,
        Long parentId,
        @Size(max = 64) String templateCode) {

    /** 兼容构造（REQ-20261122-102954 之前调用面）：parentId 缺省 null=顶层。 */
    public AdminTenantInitRequest(String tenantCode, String tenantName, String remark,
                                  String adminUsername, String adminNickname) {
        this(tenantCode, tenantName, remark, adminUsername, adminNickname, null, null);
    }

    /** 兼容构造（REQ-20261122-102954 调用面）：templateCode 缺省 null=无模板（现行为）。 */
    public AdminTenantInitRequest(String tenantCode, String tenantName, String remark,
                                  String adminUsername, String adminNickname, Long parentId) {
        this(tenantCode, tenantName, remark, adminUsername, adminNickname, parentId, null);
    }
}
