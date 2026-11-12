package com.acme.framework.cache.config;

import com.acme.common.serialization.Json;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.core.support.TransactionEventPublisher;
import com.acme.framework.cache.internal.CaffeineL1Cache;
import com.acme.framework.cache.internal.JsonRedisSerializer;
import com.acme.framework.cache.internal.RedisEntryLock;
import com.acme.framework.cache.internal.RedisL2Cache;
import com.acme.framework.cache.interceptor.L1InvalidationBus;
import com.acme.framework.cache.interceptor.PenetrationGuard;
import com.acme.framework.cache.support.CacheKeys;
import com.acme.framework.cache.support.TwoLevelCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * framework-cache 总装（FR-030 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * L2/广播依赖 Redis（spring-boot-data-redis 细粒度，BR-021）；键规范经 core {@link KeyComposer}（4.3）。
 * Json 门面取全局 bean（2.5.1），缺失启动显式失败，不自建 mapper。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("cache")
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 缓存 key 规范（FR-008）。 */
    @Bean
    public CacheKeys cacheKeys(KeyComposer keyComposer) {
        return new CacheKeys(keyComposer);
    }

    /** L1（FR-005）。 */
    @Bean
    public CaffeineL1Cache caffeineL1Cache(CacheProperties properties) {
        return new CaffeineL1Cache(properties.getL1().getMaxSize(), properties.getL1().getTtlSeconds());
    }

    /** 值序列化（架构设计 6.1：common-serialization Json，Jackson 3）。 */
    @Bean
    public JsonRedisSerializer cacheJsonSerializer(ObjectProvider<Json> jsons, ObjectProvider<JsonMapper> mappers) {
        return new JsonRedisSerializer(resolveJson(jsons, mappers));
    }

    /** L2（FR-005）。 */
    @Bean
    public RedisL2Cache redisL2Cache(StringRedisTemplate redisTemplate, JsonRedisSerializer serializer) {
        return new RedisL2Cache(redisTemplate, serializer);
    }

    /** 防击穿门（FR-007）。 */
    @Bean
    public PenetrationGuard penetrationGuard(CacheProperties properties, StringRedisTemplate redisTemplate,
                                             KeyComposer keyComposer) {
        CacheProperties.PenetrationGuard guard = properties.getPenetrationGuard();
        PenetrationGuard.EntryLock entryLock = guard.isEnabled()
                ? new RedisEntryLock(redisTemplate, keyComposer.compose("c", "pg") + ":")
                : null;
        return new PenetrationGuard(guard.isEnabled(), guard.getLeaseMillis(), guard.getWaitMillis(), entryLock);
    }

    /** L1 失效广播（FR-006，Q-D002：Redis Pub/Sub；来源实例 id 跳过自身）。 */
    @Bean
    public L1InvalidationBus l1InvalidationBus(StringRedisTemplate redisTemplate, CaffeineL1Cache l1,
                                               KeyComposer keyComposer, Environment environment) {
        String instanceId = environment.getProperty("spring.application.instance-id",
                java.util.UUID.randomUUID().toString());
        return new L1InvalidationBus(redisTemplate, L1InvalidationBus.channelOf(keyComposer.servicePrefix()), l1,
                instanceId);
    }

    /** 广播订阅容器（通道 acme:cache:invalidate:{service}）。 */
    @Bean
    @ConditionalOnMissingBean(name = "cacheInvalidationListenerContainer")
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(RedisConnectionFactory connectionFactory,
                                                                            L1InvalidationBus bus,
                                                                            KeyComposer keyComposer) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bus, new ChannelTopic(L1InvalidationBus.channelOf(keyComposer.servicePrefix())));
        return container;
    }

    /** 二级缓存门面（FR-005/006/007；失效经事务提交后事件延迟，FR-003）。 */
    @Bean
    public TwoLevelCache twoLevelCache(CaffeineL1Cache l1, RedisL2Cache l2, L1InvalidationBus bus,
                                       PenetrationGuard guard, CacheProperties properties,
                                       ObjectProvider<TransactionEventPublisher> eventPublisher) {
        return new TwoLevelCache(l1, l2, bus, guard, properties, eventPublisher);
    }

    /**
     * 共用 Json 门面（2.5.1）：优先全局 Json bean；仅当已有 JsonMapper bean 时包装；
     * 两者缺失启动显式失败（不再自建 mapper，防与全局分叉）。
     */
    private static Json resolveJson(ObjectProvider<Json> jsons, ObjectProvider<JsonMapper> mappers) {
        Json existing = jsons.getIfAvailable();
        if (existing != null) {
            return existing;
        }
        JsonMapper mapper = mappers.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException(
                    "缺少 Json/JsonMapper bean：请引入 framework-jackson 或声明 JsonMapper（2.5.1）");
        }
        return new Json(mapper);
    }
}
