package com.acme.framework.stream.internal;

import com.acme.framework.stream.interceptor.ConsumeChannelInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.messaging.support.InterceptableChannel;

/**
 * 消费管线接线（internal）：入站绑定通道（{@code *-in-0}）创建时挂载 {@link ConsumeChannelInterceptor}；
 * 通道为绑定 FactoryBean 产物（SCSt 5.x），按 bean 名确定性接线，不依赖全局拦截器语义。
 */
public class InboundPipelineRegistrar implements BeanPostProcessor {

    private static final String INBOUND_SUFFIX = "-in-0";

    private final ObjectProvider<ConsumeChannelInterceptor> pipeline;

    public InboundPipelineRegistrar(ObjectProvider<ConsumeChannelInterceptor> pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof InterceptableChannel channel && beanName != null && beanName.endsWith(INBOUND_SUFFIX)) {
            ConsumeChannelInterceptor consumePipeline = pipeline.getIfAvailable();
            if (consumePipeline != null) {
                channel.addInterceptor(consumePipeline);
            }
        }
        return bean;
    }
}
