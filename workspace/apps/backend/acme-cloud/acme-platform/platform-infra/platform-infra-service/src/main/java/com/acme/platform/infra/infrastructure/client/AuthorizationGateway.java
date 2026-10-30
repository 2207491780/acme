package com.acme.platform.infra.infrastructure.client;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import org.springframework.stereotype.Component;

/**
 * 跨域权限判定网关（权限目录平台级统一、错误码归 system 段，与 platform-auth AuthorizationGateway 同先例）：
 * 调用方主体（网关注入的内部令牌 claims → UserContext）经 platform-system 判定；
 * 未授权抛 PERMISSION_DENIED（403）。同步调用链：网关→infra→system = 3 压线合规（M-07）。
 */
@Component
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

    /** 判定当前主体是否具备权限点（不抛；判定服务异常按无权限处理，fail-closed）。 */
    public boolean hasPermission(String permissionCode) {
        try {
            ContractResult<Boolean> result = authorizationClient.hasPermission(permissionCode);
            return result != null && Boolean.TRUE.equals(result.data());
        } catch (RuntimeException ex) {
            return false;
        }
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
