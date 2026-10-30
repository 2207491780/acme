package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 服务间权限判定契约（FR-008 接口级权限点跨域执行面）：调用方角色集取自内部令牌 claims
 * （framework-security 校验后写入 UserContext），服务端对照 sys_role_permission 判定；
 * 跨域权限目录平台级统一（权限点数据归 platform-system 独占，M-03）。
 */
@FeignClient(name = "platform-system", contextId = "authorizationClient")
public interface AuthorizationClient {

    /**
     * 判定调用方角色集是否具备指定权限点。
     *
     * @param code 权限点编码（如 {@link SystemPermissions#AUTH_CREDENTIAL_MANAGE}）
     * @return 信封携带判定结果（true=具备；内置超管短路放行）
     */
    @GetMapping("/internal/v1/authorization/permissions/{code}")
    ContractResult<Boolean> hasPermission(@PathVariable("code") String code);
}
