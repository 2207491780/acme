package com.acme.framework.stream.internal;

import com.acme.common.core.BizException;
import com.acme.framework.stream.config.StreamProperties;
import com.acme.framework.stream.support.StreamError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 死信通道启动校验（FR-015 异常分支、BR-009：每个消费主题必须配置死信通道）：
 * kafka 绑定需 enable-dlq/dlq-name；rocketmq binder 原生死信；缺失按 acme.stream.dlq.required 处置。
 * 绑定名识别放宽：{@code *-in-0} 与传统 {@code input} 等非 {@code -in-0} 名同样纳入；
 * 非 Enumerable 源走 {@code getProperty} 兜底枚举。
 */
public class DlqConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(DlqConfigValidator.class);

    private static final String BINDINGS_PREFIX = "spring.cloud.stream.bindings.";

    private final Environment environment;
    private final StreamProperties properties;

    public DlqConfigValidator(Environment environment, StreamProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /** 启动校验：缺失时显式失败（required）或 WARN 告警。 */
    public void validate() {
        for (String binding : inputBindings()) {
            checkBinding(binding);
        }
    }

    /** 输入绑定名（*-in-0 与传统 input 等同纳入；Enumerable 源 + getProperty 兜底）。 */
    Set<String> inputBindings() {
        Set<String> bindings = new LinkedHashSet<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        String binding = bindingOfDestinationProperty(name);
                        if (binding != null) {
                            bindings.add(binding);
                        }
                    }
                }
            }
        }
        // 非 Enumerable 源兜底：按 input-bindings 显式清单 + 常见 -in-0 命名逐个 getProperty 试探
        for (String binding : explicitInputBindings()) {
            if (environment.getProperty(BINDINGS_PREFIX + binding + ".destination") != null) {
                bindings.add(binding);
            }
        }
        return bindings;
    }

    private List<String> explicitInputBindings() {
        List<String> names = new ArrayList<>();
        String declared = environment.getProperty("spring.cloud.stream.input-bindings");
        if (declared != null && !declared.isBlank()) {
            for (String part : declared.split("[,;]")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        String definition = environment.getProperty("spring.cloud.function.definition");
        if (definition != null && !definition.isBlank()) {
            for (String part : definition.split("[,|;]")) {
                String function = part.trim();
                if (!function.isEmpty()) {
                    names.add(function + "-in-0");
                }
            }
        }
        return names;
    }

    private static String bindingOfDestinationProperty(String propertyName) {
        if (propertyName == null || !propertyName.startsWith(BINDINGS_PREFIX)
                || !propertyName.endsWith(".destination")) {
            return null;
        }
        String binding = propertyName.substring(BINDINGS_PREFIX.length(),
                propertyName.length() - ".destination".length());
        // 输入绑定：-in-0 后缀或传统非 -in-0 名（input 等）均纳入（P2 盲区收紧）
        return binding.isEmpty() ? null : binding;
    }

    private void checkBinding(String binding) {
        if (binding.endsWith("-out-0") || "output".equals(binding)) {
            // 输出绑定不校验死信（FR-015 只管消费主题）
            return;
        }
        String binder = environment.getProperty(BINDINGS_PREFIX + binding + ".binder", "default");
        String binderType = environment.getProperty("spring.cloud.stream.binders." + binder + ".type");
        if (binderType == null || binderType.isBlank()) {
            binderType = defaultBinderType();
        }
        if ("rocketmq".equalsIgnoreCase(binderType)) {
            // RocketMQ binder 原生死信（POC-3 口径）
            return;
        }
        if (!"kafka".equalsIgnoreCase(binderType)) {
            log.warn("无法判定 binder 死信语义，跳过校验（FR-015）: binding={} binderType={}", binding, binderType);
            return;
        }
        String enableDlq = environment.getProperty(
                "spring.cloud.stream.kafka.bindings." + binding + ".consumer.enable-dlq");
        String dlqName = environment.getProperty(
                "spring.cloud.stream.kafka.bindings." + binding + ".consumer.dlq-name");
        boolean configured = "true".equalsIgnoreCase(enableDlq) || (dlqName != null && !dlqName.isBlank());
        if (configured) {
            return;
        }
        if (properties.getDlq().isRequired()) {
            throw new BizException(StreamError.DLQ_NOT_CONFIGURED,
                    "消费绑定未配置死信通道（FR-015、BR-009）: " + binding);
        }
        log.warn("消费绑定未配置死信通道（acme.stream.dlq.required=false，降级告警）: {}", binding);
    }

    private static String defaultBinderType() {
        if (ClassUtils.isPresent("org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder",
                DlqConfigValidator.class.getClassLoader())) {
            return "kafka";
        }
        return "unknown";
    }
}
