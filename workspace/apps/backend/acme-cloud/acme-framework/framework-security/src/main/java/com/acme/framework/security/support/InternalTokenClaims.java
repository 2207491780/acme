package com.acme.framework.security.support;

import com.acme.common.context.Channel;

import java.util.Set;

/**
 * 内部令牌声明摘要（FR-014、7.7 claims 集合）：sub=userId、tenantId、channel、roles[]、tokenType、iss、jti。
 * deviceId/appVersion 不入 claims（走 ServiceHeaders 头，7.6）。
 */
public record InternalTokenClaims(String userId, String tenantId, Channel channel,
                                  Set<String> roles, TokenType tokenType, String issuer, String tokenId) {

    public InternalTokenClaims {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
