package com.acme.framework.feign.internal;

import com.acme.framework.feign.config.FeignProperties;
import com.acme.framework.feign.interceptor.ResilientFeignClient;
import feign.Capability;
import feign.Client;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Client 装饰接线（internal，Q-D004）：经 feign {@link Capability} 包裹 loadbalancer/缺省 Client，
 * 超时/幂等重试/熔断统一收敛 {@link ResilientFeignClient}。
 */
public class ResilientClientCapability implements Capability {

    private final FeignProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ResilientClientCapability(FeignProperties properties, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Client enrich(Client client) {
        return new ResilientFeignClient(client, properties, circuitBreakerRegistry);
    }
}
