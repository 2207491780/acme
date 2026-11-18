package com.acme.framework.stream.config;

import com.acme.common.serialization.Json;
import com.acme.common.serialization.JsonModules;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.stream.internal.BinderRouting;
import com.acme.framework.stream.internal.DedupStore;
import com.acme.framework.stream.internal.DlqConfigValidator;
import com.acme.framework.stream.internal.InboundPipelineRegistrar;
import com.acme.framework.stream.internal.MessageDispatcher;
import com.acme.framework.stream.internal.RedisDedupStore;
import com.acme.framework.stream.internal.StreamBridgeDispatcher;
import com.acme.framework.stream.interceptor.ConsumeChannelInterceptor;
import com.acme.framework.stream.interceptor.ConsumePipeline;
import com.acme.framework.stream.support.IdempotentGuard;
import com.acme.framework.stream.support.MessageSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

/**
 * framework-stream 总装（FR-030 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 消费幂等门不可关闭（取舍 #5，仅随模块开关整体启停）；binder 实现由使用方引入（8.6）。
 * 发送侧头注入单点收敛 {@link MessageSender}（BR-014）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("stream")
@ConditionalOnClass(StreamBridge.class)
@EnableConfigurationProperties(StreamProperties.class)
public class StreamAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 去重存储（FR-014，Q-002 Redis 去重键三态）。 */
    @Bean
    @ConditionalOnMissingBean(DedupStore.class)
    public DedupStore redisDedupStore(StringRedisTemplate redisTemplate) {
        return new RedisDedupStore(redisTemplate);
    }

    /** 消费幂等门（FR-014）。 */
    @Bean
    @ConditionalOnMissingBean(IdempotentGuard.class)
    public IdempotentGuard idempotentGuard(DedupStore store, KeyComposer keyComposer, StreamProperties properties) {
        return new IdempotentGuard(store, keyComposer, properties);
    }

    /** 多 binder 路由校验（FR-016）。 */
    @Bean
    public BinderRouting binderRouting(Environment environment) {
        return new BinderRouting(environment);
    }

    /** 发送分发面（internal）。 */
    @Bean
    @ConditionalOnMissingBean(MessageDispatcher.class)
    public MessageDispatcher messageDispatcher(ObjectProvider<StreamBridge> streamBridge) {
        return new StreamBridgeDispatcher(streamBridge::getObject);
    }

    /** 发送门面（FR-013；头注入单点，BR-014）。 */
    @Bean
    @ConditionalOnMissingBean(MessageSender.class)
    public MessageSender messageSender(MessageDispatcher dispatcher,
                                       ObjectProvider<Json> jsons, ObjectProvider<JsonMapper> mappers,
                                       BinderRouting binderRouting) {
        return new MessageSender(dispatcher, resolveJson(jsons, mappers), binderRouting);
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

    /** 消费通道拦截器（FR-014/024/032，Q-D003；G3a Q-1 双件之一）。 */
    @Bean
    public ConsumeChannelInterceptor consumeChannelInterceptor(IdempotentGuard guard, StreamProperties properties) {
        return new ConsumeChannelInterceptor(guard, properties);
    }

    /** 消费管线入站接线（internal；延迟解析避免 BeanPostProcessor 早期初始化）。 */
    @Bean
    public static InboundPipelineRegistrar streamInboundPipelineRegistrar(ObjectProvider<ConsumeChannelInterceptor> pipeline) {
        return new InboundPipelineRegistrar(pipeline);
    }

    /** 消费函数入口包装器（FR-014，G3a Q-1 双件之二：重复消息干净跳过；BeanPostProcessor 静态注册）。 */
    @Bean
    public static ConsumePipeline streamConsumePipeline(Environment environment) {
        return new ConsumePipeline(environment);
    }

    /** 死信通道启动校验（FR-015、BR-009）。 */
    @Bean
    public DlqConfigValidator dlqConfigValidator(Environment environment, StreamProperties properties) {
        return new DlqConfigValidator(environment, properties);
    }

    /** 启动期执行死信校验。 */
    @Bean
    public SmartLifecycle dlqValidationLifecycle(DlqConfigValidator validator) {
        return new SmartLifecycle() {

            private volatile boolean running;

            @Override
            public void start() {
                validator.validate();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
