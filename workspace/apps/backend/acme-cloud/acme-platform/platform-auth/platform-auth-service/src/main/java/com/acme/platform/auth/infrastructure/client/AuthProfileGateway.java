package com.acme.platform.auth.infrastructure.client;

import com.acme.common.core.BizException;
import com.acme.framework.core.support.ServiceIdentityScope;
import com.acme.platform.auth.AuthError;
import com.acme.platform.system.api.AuthProfileClient;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        return ServiceIdentityScope.runAs(SERVICE_IDENTITY, () -> {
            try {
                ContractResult<AuthProfileView> result = authProfileClient.findByUsername(username);
                return result.payload();
            } catch (BizException ex) {
                if (SystemErrorCodes.USER_NOT_FOUND.equals(ex.errorCode().code())) {
                    throw new BizException(AuthError.LOGIN_FAILED);
                }
                throw ex;
            }
        });
    }

    /**
     * 按 userId 批量查画像（REQ-20261114-120105：在线用户列表 username / 超管判定富化）；
     * 以服务身份主体调用（Q-D08 同口径）。查不到的 userId 不在结果中（展示缺省处理）。
     */
    public Map<Long, AuthProfileView> findByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return ServiceIdentityScope.runAs(SERVICE_IDENTITY, () -> {
            ContractResult<List<AuthProfileView>> result = authProfileClient.findByUserIds(List.copyOf(userIds));
            List<AuthProfileView> profiles = result.payload();
            Map<Long, AuthProfileView> mapped = new HashMap<>();
            for (AuthProfileView profile : profiles == null ? List.<AuthProfileView>of() : profiles) {
                try {
                    mapped.put(Long.parseLong(profile.userId()), profile);
                } catch (NumberFormatException ignored) {
                    // 画像 userId 非数值（脏数据）：跳过，不影响其余条目
                }
            }
            return mapped;
        });
    }
}
