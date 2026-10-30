package com.acme.framework.idempotent.config;

import com.acme.common.serialization.Json;
import com.acme.common.serialization.JsonModules;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.idempotent.aspect.IdempotentAspect;
import com.acme.framework.idempotent.internal.HttpIdempotentGate;
import com.acme.framework.idempotent.internal.IdempotentRecordStore;
import com.acme.framework.idempotent.internal.JsonResultCodec;
import com.acme.framework.idempotent.internal.MqIdempotentGate;
import com.acme.framework.idempotent.internal.RedisIdempotentRecordStore;
import com.acme.framework.idempotent.internal.ResultCodec;
import com.acme.framework.stream.config.StreamAutoConfiguration;
import com.acme.framework.stream.support.IdempotentGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

/**
 * framework-idempotent 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * HTTP 入口自持 Redis 三态记录（Q-006）；MQ 入口包装 framework-stream {@link IdempotentGuard}
 * （BR-007 复用不重构，D-003）；装配顺序 after stream（IdempotentGuard bean 条件可见）。
 */
@AutoConfiguration(after = StreamAutoConfiguration.class)
@ConditionalOnAcmeModule("idempotent")
@EnableConfigurationProperties(IdempotentProperties.class)
public class IdempotentAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** HTTP 幂等记录存储（FR-003、Q-006 Redis 单 hash）。 */
    @Bean
    @ConditionalOnMissingBean(IdempotentRecordStore.class)
    public IdempotentRecordStore idempotentRecordStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotentRecordStore(redisTemplate);
    }

    /** 结果缓存编解码（2.5.1 统一 Module 集，framework-stream resolveJson 先例）。 */
    @Bean
    @ConditionalOnMissingBean(ResultCodec.class)
    public ResultCodec idempotentResultCodec(ObjectProvider<Json> jsons, ObjectProvider<JsonMapper> mappers) {
        return new JsonResultCodec(resolveJson(jsons, mappers));
    }

    /** HTTP 幂等门（FR-001）。 */
    @Bean
    @ConditionalOnMissingBean(HttpIdempotentGate.class)
    public HttpIdempotentGate httpIdempotentGate(IdempotentRecordStore store, ResultCodec codec,
                                                IdempotentProperties properties) {
        return new HttpIdempotentGate(store, codec, properties);
    }

    /** MQ 入口幂等门（FR-002、BR-007：包装 stream IdempotentGuard，无第二套去重实现）。 */
    @Bean
    @ConditionalOnBean(IdempotentGuard.class)
    @ConditionalOnMissingBean(MqIdempotentGate.class)
    public MqIdempotentGate mqIdempotentGate(IdempotentGuard guard) {
        return new MqIdempotentGate(guard);
    }

    /** HTTP 幂等切面（FR-001）。 */
    @Bean
    public IdempotentAspect idempotentAspect(HttpIdempotentGate gate, KeyComposer keyComposer,
                                             IdempotentProperties properties) {
        return new IdempotentAspect(gate, keyComposer, properties);
    }

    /** 共用 Json 门面（2.5.1/2.5.2）：优先注入实例，缺失时按标准 Module 集自建。 */
    private static Json resolveJson(ObjectProvider<Json> jsons, ObjectProvider<JsonMapper> mappers) {
        Json existing = jsons.getIfAvailable();
        if (existing != null) {
            return existing;
        }
        JsonMapper mapper = mappers.getIfAvailable();
        if (mapper == null) {
            JsonMapper.Builder builder = JsonMapper.builder();
            for (JacksonModule module : JsonModules.standard()) {
                builder.addModule(module);
            }
            mapper = builder.build();
        }
        return new Json(mapper);
    }
}
