package com.acme.platform.auth.infrastructure.client;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.stereotype.Component;

/**
 * 跨域权限判定网关（FR-008 接口级权限点在 auth 域的执行面）：
 * 调用方主体（经网关注入的内部令牌 claims）经 platform-system 判定；未授权抛
 * platform-system-07-0001 PERMISSION_DENIED（403，权限目录平台级统一、错误码归 system 段，openapi 契约）。
 */
@Component
public class AuthorizationGateway {

    private final AuthorizationClient authorizationClient;

    public AuthorizationGateway(AuthorizationClient authorizationClient) {
        this.authorizationClient = authorizationClient;
    }

    /** 要求当前主体具备权限点，未授权抛 PERMISSION_DENIED（403）。 */
    public void requirePermission(String permissionCode) {
        ContractResult<Boolean> result = authorizationClient.hasPermission(permissionCode);
        Boolean granted = result == null ? null : result.data();
        if (!Boolean.TRUE.equals(granted)) {
            throw new BizException(new CrossDomainPermissionDenied());
        }
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
