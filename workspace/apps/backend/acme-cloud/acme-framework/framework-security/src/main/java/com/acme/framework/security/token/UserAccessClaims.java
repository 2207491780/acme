package com.acme.framework.security.token;

import com.acme.common.context.Channel;

import java.util.Set;

/**
 * 用户访问令牌声明摘要（REQ-20261118-002130 确认④：用户令牌验签逻辑从 framework-gateway
 * 抽取到 framework-security 的无 WebFlux 载体后，本类型即验签结果——claims 契约对齐
 * platform-auth {@code UserTokenIssuer} 签发集：sub/tenantId/channel/roles/tokenType/jti）。
 * 值对象不可变（BR-005）；framework-gateway 的 {@code UserTokenClaims} 由本类型映射产出
 * （验签语义单源，gateway 模块仅保留 SPI 与错误码段）。
 *
 * @param userId    主体（claims sub；platform-auth 签发时为用户 ID 字符串）
 * @param tenantId  租户（claims tenantId；可空——登录前/平台级）
 * @param channel   端别（claims channel；可空）
 * @param roles     角色编码集（claims roles[]；恒非 null）
 * @param tokenType 令牌类型（access/refresh，11.4）
 * @param tokenId   会话令牌标识（claims jti；撤销集键，Q-D02）
 */
public record UserAccessClaims(String userId, String tenantId, Channel channel, Set<String> roles,
                               String tokenType, String tokenId) {

    public UserAccessClaims {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
