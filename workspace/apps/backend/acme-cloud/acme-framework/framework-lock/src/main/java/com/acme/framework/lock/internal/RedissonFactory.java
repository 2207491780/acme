package com.acme.framework.lock.internal;

import com.acme.framework.lock.config.LockProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.core.env.Environment;

/**
 * Redisson 裸客户端装配（FR-009、架构设计 7.1/2.4：org.redisson:redisson，不用 starter）；
 * 连接参数统一取 {@code spring.data.redis.*}（14.3.3 单一事实源，删除 acme.lock.redis.* 双源）；
 * Redisson 编解码触碰面收敛本包（Modules.md 3.5），锁场景默认不用 JSON codec。
 */
public final class RedissonFactory {

    private RedissonFactory() {
    }

    public static RedissonClient create(LockProperties properties, Environment environment) {
        Config config = new Config();
        var singleServer = config.useSingleServer()
                .setAddress(resolveAddress(environment))
                .setDatabase(environment.getProperty("spring.data.redis.database", Integer.class, 0));
        String username = environment.getProperty("spring.data.redis.username");
        if (username != null && !username.isBlank()) {
            singleServer.setUsername(username.trim());
        }
        String password = environment.getProperty("spring.data.redis.password");
        if (password != null && !password.isBlank()) {
            singleServer.setPassword(password);
        }
        // 看门狗租期（FR-011；默认 lease 未指定时自动续租）
        config.setLockWatchdogTimeout(Math.max(properties.getWatchdogSeconds(), 1) * 1000L);
        return Redisson.create(config);
    }

    /** 连接地址：优先 spring.data.redis.url，否则 host:port（缺省 localhost:6379）。 */
    private static String resolveAddress(Environment environment) {
        String url = environment.getProperty("spring.data.redis.url");
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        String host = environment.getProperty("spring.data.redis.host", "localhost");
        int port = environment.getProperty("spring.data.redis.port", Integer.class, 6379);
        return "redis://" + host + ":" + port;
    }
}
