package com.acme.framework.stream.internal;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 消费重试接线（FR-015、10.3.3 指数退避+上限真实生效）：把 {@code acme.stream.retry.*}
 * 映射为各 consumer 绑定的 {@code max-attempts/back-off-*}（低优先级默认值，per-binding 显式配置仍可覆盖）。
 * 经 spring.factories 注册（Modules.md 3.5 internal，模块外不得引用）。
 */
public class RetryBindingDefaults implements EnvironmentPostProcessor {

    private static final String BINDINGS_PREFIX = "spring.cloud.stream.bindings.";

    private static final String SOURCE_NAME = "acmeStreamRetryBindingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, org.springframework.boot.SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        if (!environment.getProperty("acme.stream.enabled", Boolean.class, Boolean.TRUE)) {
            return;
        }
        Map<String, Object> defaults = new HashMap<>();
        String maxAttempts = environment.getProperty("acme.stream.retry.max-attempts", "3");
        String initial = environment.getProperty("acme.stream.retry.backoff-initial-millis", "100");
        String multiplier = environment.getProperty("acme.stream.retry.backoff-multiplier", "2");
        String maxInterval = environment.getProperty("acme.stream.retry.backoff-max-millis", "2000");
        for (String binding : inputBindings(environment)) {
            String prefix = BINDINGS_PREFIX + binding + ".consumer.";
            defaults.put(prefix + "max-attempts", maxAttempts);
            defaults.put(prefix + "back-off-initial-interval", initial);
            defaults.put(prefix + "back-off-multiplier", multiplier);
            defaults.put(prefix + "back-off-max-interval", maxInterval);
        }
        if (defaults.isEmpty()) {
            return;
        }
        // addLast：用户 per-binding 显式配置优先（更具体的配置面覆盖全局映射）
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }

    /** 输入绑定名：显式 {@code *-in-0} 绑定 + {@code spring.cloud.function.definition} 推导。 */
    private static Set<String> inputBindings(ConfigurableEnvironment environment) {
        Set<String> bindings = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(BINDINGS_PREFIX) || !name.endsWith(".destination")) {
                    continue;
                }
                String binding = name.substring(BINDINGS_PREFIX.length(),
                        name.length() - ".destination".length());
                if (binding.endsWith("-in-0")) {
                    bindings.add(binding);
                }
            }
        }
        String definition = environment.getProperty("spring.cloud.function.definition");
        if (definition != null && !definition.isBlank()) {
            for (String part : definition.split("[,|;]")) {
                String function = part.trim();
                if (!function.isEmpty()) {
                    bindings.add(function + "-in-0");
                }
            }
        }
        String declared = environment.getProperty("spring.cloud.stream.input-bindings");
        if (declared != null && !declared.isBlank()) {
            for (String part : declared.split("[,;]")) {
                String binding = part.trim();
                if (!binding.isEmpty()) {
                    bindings.add(binding);
                }
            }
        }
        return bindings;
    }
}
