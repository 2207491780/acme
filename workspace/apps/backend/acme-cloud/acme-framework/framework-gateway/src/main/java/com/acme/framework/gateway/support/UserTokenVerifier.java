package com.acme.framework.gateway.support;

/**
 * 用户令牌验签 SPI（FR-019、8.2）；缺省实现 JWS HS256（Q-D01 家族），platform-auth 契约定稿后可换实现。
 */
public interface UserTokenVerifier {

    /**
     * 验签并解析用户令牌（FR-019）。
     *
     * @throws com.acme.common.core.BizException 验签失败/过期（调用方按 GatewayError 映射）
     */
    UserTokenClaims verify(String token);
}
