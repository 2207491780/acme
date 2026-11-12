package com.acme.framework.gateway.internal;

import com.acme.framework.gateway.support.UserTokenRevocationChecker;

/**
 * 撤销检查缺省实现（Q-D02）：永不撤销，行为与未引入 SPI 一致（Modules.md 3.5 可关闭性）。
 */
public final class NeverRevokedTokenChecker implements UserTokenRevocationChecker {

    @Override
    public boolean isRevoked(String tokenId) {
        return false;
    }
}
