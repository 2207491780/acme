package com.acme.platform.auth.infrastructure.client;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.system.api.AuthProfileClient;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 登录认证画像网关（架构设计 3.3，Q-D08）：登录场景无用户主体，
 * 以服务身份主体（内部令牌 userId=platform-auth）调用（system 侧 @IgnoreTenant 平台级查询）；
 * 查无此用户按统一失败映射 LOGIN_FAILED（FR-005 不区分账号存在性），其余下游错误透传（可观测不掩盖故障）。
 */
@Component
public class AuthProfileGateway {

    /** 服务身份主体标识（Q-D08：内部令牌 userId 填服务名）。 */
    public static final String SERVICE_IDENTITY = "platform-auth";

    private final AuthProfileClient authProfileClient;

    public AuthProfileGateway(AuthProfileClient authProfileClient) {
        this.authProfileClient = authProfileClient;
    }

    /** 按用户名查画像；404 → 统一 LOGIN_FAILED。 */
    public AuthProfileView findByUsername(String username) {
        UserContext previous = UserContext.get();
        UserContext.bind(new UserContext(SERVICE_IDENTITY, Set.of()));
        try {
            ContractResult<AuthProfileView> result = authProfileClient.findByUsername(username);
            return result.payload();
        } catch (BizException ex) {
            if (SystemErrorCodes.USER_NOT_FOUND.equals(ex.errorCode().code())) {
                throw new BizException(AuthError.LOGIN_FAILED);
            }
            throw ex;
        } finally {
            UserContext.bind(previous);
        }
    }
}
