package com.acme.framework.security.support;

/**
 * 内部令牌校验 SPI（FR-014、7.2；7.7 接缝抽象：换算法仅换 internal 实现、claims 契约不变）。
 */
public interface InternalTokenVerifier {

    /**
     * 校验并解析内部令牌（FR-014）。
     *
     * @throws com.acme.common.core.BizException 校验失败（缺失/无效/过期由调用方按 SecurityError 映射）
     */
    InternalTokenClaims verify(String token);
}
