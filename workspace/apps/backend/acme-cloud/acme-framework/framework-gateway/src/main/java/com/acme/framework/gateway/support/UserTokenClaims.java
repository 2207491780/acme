package com.acme.framework.gateway.support;

import com.acme.common.context.Channel;

import java.util.Set;

/**
 * 用户令牌声明摘要（FR-019、8.2）：网关验签结果载体；用户令牌格式属 platform-auth 未来契约（8.2），
 * P3 验收供给见架构设计 11.3。
 */
public record UserTokenClaims(String userId, String tenantId, Channel channel, Set<String> roles,
                              String tokenType, String tokenId) {

    public UserTokenClaims {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
