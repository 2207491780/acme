package com.acme.framework.stream.interceptor;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 消费函数入口包装器（FR-014，G3a Q-1 双件之二）：重复消息在入口处干净跳过（投递成功、业务不执行，AC-015）；
 * 与 {@link ConsumeChannelInterceptor} 通道拦截器配套（拦截器取真实消息头预解析键，本包装器执行跳过/放行）。
 *
 * <p>只包装实际绑定的函数 bean（{@code spring.cloud.function.definition} 或 {@code *-in-0} 绑定面），
 * 非消息函数不包装、不污染泛型面；包装类为实现原接口的具名类（保留 {@link Consumer}/{@link Function}
 * 接口形态，供 SCSt 泛型发现，禁止裸 {@code Consumer<Object>} lambda）。</p>
 */
public class ConsumePipeline implements BeanPostProcessor {

    private static final String INBOUND_SUFFIX = "-in-0";

    private final Environment environment;

    private volatile Set<String> boundFunctionNames;

    public ConsumePipeline(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (beanName == null || !isBoundFunctionName(beanName)) {
            return bean;
        }
        if (bean instanceof Consumer<?> consumer) {
            return wrapConsumer(consumer);
        }
        if (bean instanceof Function<?, ?> function) {
            return wrapFunction(function);
        }
        return bean;
    }

    private boolean isBoundFunctionName(String beanName) {
        Set<String> names = boundFunctionNames;
        if (names == null) {
            names = resolveBoundFunctionNames();
            boundFunctionNames = names;
        }
        return names.contains(beanName);
    }

    /** 绑定函数名：{@code spring.cloud.function.definition}（支持 | ; , 组合）+ 显式 {@code *-in-0} 绑定。 */
    private Set<String> resolveBoundFunctionNames() {
        Set<String> names = new LinkedHashSet<>();
        String definition = environment.getProperty("spring.cloud.function.definition");
        if (definition != null && !definition.isBlank()) {
            for (String part : definition.split("[,|;]")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    String function = inboundFunctionOf(name);
                    if (function != null) {
                        names.add(function);
                    }
                }
            }
        }
        return Set.copyOf(names);
    }

    private static String inboundFunctionOf(String propertyName) {
        String prefix = "spring.cloud.stream.bindings.";
        if (!propertyName.startsWith(prefix) || !propertyName.endsWith(".destination")) {
            return null;
        }
        String binding = propertyName.substring(prefix.length(),
                propertyName.length() - ".destination".length());
        return binding.endsWith(INBOUND_SUFFIX)
                ? binding.substring(0, binding.length() - INBOUND_SUFFIX.length())
                : null;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> wrapConsumer(Consumer<?> delegate) {
        return new SkippingConsumer<>((Consumer<Object>) delegate);
    }

    @SuppressWarnings("unchecked")
    private static Function<Object, Object> wrapFunction(Function<?, ?> delegate) {
        return new SkippingFunction<>((Function<Object, Object>) delegate);
    }

    /** 跳过执行的 Consumer 具名包装（保留 Consumer 接口形态，G3a Q-1）。 */
    static final class SkippingConsumer<T> implements Consumer<T> {

        private final Consumer<T> delegate;

        SkippingConsumer(Consumer<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(T input) {
            if (ConsumeChannelInterceptor.isSkipPending()) {
                return;
            }
            delegate.accept(input);
        }
    }

    /** 跳过执行的 Function 具名包装（保留 Function 接口形态，G3a Q-1）。 */
    static final class SkippingFunction<T, R> implements Function<T, R> {

        private final Function<T, R> delegate;

        SkippingFunction(Function<T, R> delegate) {
            this.delegate = delegate;
        }

        @Override
        public R apply(T input) {
            if (ConsumeChannelInterceptor.isSkipPending()) {
                return null;
            }
            return delegate.apply(input);
        }
    }
}
