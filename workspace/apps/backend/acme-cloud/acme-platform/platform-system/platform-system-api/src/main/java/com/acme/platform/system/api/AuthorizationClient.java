package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Set;

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

    /**
     * 当前主体实查内置角色编码集（REQ-20261117-025200 超管跨租户切换前置校验，确认记录④）：
     * 角色编码仅为查询键（roles 不作信任锚），服务端按编码定位内置角色行（built_in 权威标记，
     * 保留编码防租户自建同名角色提权），返回当前主体实际持有的内置角色编码。
     *
     * @return 信封携带内置角色编码集（无内置角色返回空集）
     */
    @GetMapping("/internal/v1/authorization/builtin-roles")
    ContractResult<Set<String>> currentBuiltinRoles();
}
