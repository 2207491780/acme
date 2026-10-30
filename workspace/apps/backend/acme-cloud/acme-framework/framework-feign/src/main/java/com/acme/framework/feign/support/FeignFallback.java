package com.acme.framework.feign.support;

/**
 * 降级 SPI（FR-020、Q-D004：使用方提供兜底返回；framework 不定义业务兜底数据，BR-017）。
 */
public interface FeignFallback {

    /**
     * 降级返回（熔断开启/重试耗尽/传输失败时调用）。
     *
     * @param client 服务名（feign target）
     * @param method 方法标识
     * @param args   调用参数
     * @param cause  触发原因
     * @return 兜底返回（须与方法返回类型兼容）；抛出则显式失败
     */
    Object fallback(String client, String method, Object[] args, Throwable cause);
}
