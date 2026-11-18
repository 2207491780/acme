package com.acme.framework.gateway.support;

/**
 * 用户令牌撤销检查 SPI（platform-services-bootstrap Q-D02、FR-004 即时失效）：
 * 验签通过后由网关逐请求判定令牌是否已被撤销（登出 / 互踢 / 重置密码写入撤销集）。
 *
 * <p>缺省实现 {@code internal} 包内「永不撤销」（行为与未引入一致，Modules.md 3.5 可关闭性）；
 * 需要即时失效语义的应用（如 acme-gateway）自行注册 Redis 直查实现覆盖。
 * 实现可阻塞（过滤器内以 boundedElastic 隔离执行），每请求一查、不做本地缓存（Q-D02 取舍 #11）。</p>
 */
public interface UserTokenRevocationChecker {

    /**
     * 判定令牌是否已撤销。
     *
     * @param tokenId 令牌 jti（可空——无 jti 的令牌视为未撤销）
     * @return true=已撤销（网关按 AUTH_FAILED 拒绝，不向下游转发）
     */
    boolean isRevoked(String tokenId);
}
