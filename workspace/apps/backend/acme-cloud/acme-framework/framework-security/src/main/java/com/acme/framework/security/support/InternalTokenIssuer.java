package com.acme.framework.security.support;

import com.acme.framework.core.support.AuthenticatedSubject;

/**
 * 内部令牌签发 SPI（Q-D001 归位：与 {@link InternalTokenVerifier} 同居 framework-security，签发/校验单一事实源；
 * 7.7 接缝抽象：换算法仅换 internal 实现、claims 契约不变）。入参收敛为 framework-core {@link AuthenticatedSubject}。
 */
public interface InternalTokenIssuer {

    /** 签发内部令牌（JWS 紧凑序列，7.7）。 */
    String issue(AuthenticatedSubject subject);
}
