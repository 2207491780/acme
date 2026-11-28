package com.acme.platform.auth.infrastructure.client;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 跨域权限判定网关（FR-008 接口级权限点在 auth 域的执行面）：
 * 调用方主体（经网关注入的内部令牌 claims）经 platform-system 判定；未授权抛
 * platform-system-07-0001 PERMISSION_DENIED（403，权限目录平台级统一、错误码归 system 段，openapi 契约）。
 * 显式 bean 名（REQ-20261118-002130）：与 infra 域同名类在单体裁装下消歧，纯命名注解无行为变化。
 */
@Component("authAuthorizationGateway")
public class AuthorizationGateway {

    private final AuthorizationClient authorizationClient;

    public AuthorizationGateway(AuthorizationClient authorizationClient) {
        this.authorizationClient = authorizationClient;
    }

    /** 要求当前主体具备权限点，未授权抛 PERMISSION_DENIED（403）。 */
    public void requirePermission(String permissionCode) {
        if (!hasPermission(permissionCode)) {
            throw new BizException(new CrossDomainPermissionDenied());
        }
    }

    /** 判定当前主体是否具备权限点（不抛；判定服务异常按无权限处理，fail-closed——REQ-20261127-235808 P1-08：异常不再外溢 500，requirePermission 侧落既有 PERMISSION_DENIED 403）。 */
    public boolean hasPermission(String permissionCode) {
        try {
            ContractResult<Boolean> result = authorizationClient.hasPermission(permissionCode);
            Boolean granted = result == null ? null : result.data();
            return Boolean.TRUE.equals(granted);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 当前主体实查内置角色编码集（REQ-20261117-025200 超管跨租户切换前置校验，确认记录④）。
     * 不重绑上下文——当前主体（userId + roles）经内部令牌透传（InternalTokenRequestInterceptor
     * 取当前 UserContext 签发），system 侧以 roles 为查询键实查内置角色行（roles 不作信任锚）。
     * 查询不可达 / 空信封按空集处理（fail-closed 判定由调用方承担）。
     */
    public Set<String> currentBuiltinRoles() {
        ContractResult<Set<String>> result = authorizationClient.currentBuiltinRoles();
        Set<String> roles = result == null ? null : result.data();
        return roles == null ? Set.of() : roles;
    }

    /** 凭据管理权限快捷判定（架构设计 4.6）。 */
    public void requireCredentialManage() {
        requirePermission(SystemPermissions.AUTH_CREDENTIAL_MANAGE);
    }

    /** 借用 system 权限目录权限码（码归 platform-system 段，openapi Forbidden 契约）。 */
    private static final class CrossDomainPermissionDenied implements ErrorCode, HttpStatusAware {

        @Override
        public String code() {
            return SystemErrorCodes.PERMISSION_DENIED;
        }

        @Override
        public String defaultMessage() {
            return "无操作权限";
        }

        @Override
        public int httpStatus() {
            return 403;
        }
    }
}
