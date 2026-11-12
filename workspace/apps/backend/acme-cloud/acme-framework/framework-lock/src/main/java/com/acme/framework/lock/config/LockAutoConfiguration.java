package com.acme.framework.lock.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.lock.interceptor.DistributedLockAspect;
import com.acme.framework.lock.internal.RedissonFactory;
import com.acme.framework.lock.support.DistributedLocks;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * framework-lock 总装（FR-030 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * Redisson 连接参数取 spring.data.redis.*（14.3.3 单一事实源）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("lock")
@EnableConfigurationProperties(LockProperties.class)
public class LockAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** Redisson 客户端（FR-009；取舍 #2 裸客户端，可注册 RedissonClient 覆盖）。 */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient lockRedissonClient(LockProperties properties, Environment environment) {
        return RedissonFactory.create(properties, environment);
    }

    /** 编程式锁门面（FR-009/011/012）。 */
    @Bean
    @ConditionalOnMissingBean(DistributedLocks.class)
    public DistributedLocks distributedLocks(RedissonClient redissonClient, KeyComposer keyComposer,
                                             LockProperties properties) {
        return new DistributedLocks(redissonClient, keyComposer, properties);
    }

    /** 注解式互斥切面（FR-010）。 */
    @Bean
    public DistributedLockAspect distributedLockAspect(DistributedLocks locks) {
        return new DistributedLockAspect(locks);
    }
}
