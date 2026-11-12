package com.acme.framework.core.internal;

import com.acme.framework.core.config.ConditionalOnAcmeModule;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * 模块开关条件实现（FR-005）：映射 {@code acme.<module>.enabled}，缺省开启（matchIfMissing，Q-001）。
 * 取值经 {@code environment.getProperty(key, Boolean.class, ...)} 与 {@code @ConfigurationProperties}
 * 松散绑定同源（on/off/yes/no 等宽松值一致，3.5 可关闭性）。
 */
public class AcmeModuleCondition extends SpringBootCondition {

    private static final String ENABLED_KEY_FORMAT = "acme.%s.enabled";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnAcmeModule.class.getName());
        String module = attributes == null ? null : (String) attributes.get("value");
        if (module == null || module.isBlank()) {
            return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnAcmeModule.class)
                    .didNotFind("module name").atAll());
        }
        String key = ENABLED_KEY_FORMAT.formatted(module);
        boolean enabled = context.getEnvironment().getProperty(key, Boolean.class, Boolean.TRUE);
        ConditionMessage.Builder message =
                ConditionMessage.forCondition(ConditionalOnAcmeModule.class, "(acme module: " + module + ")");
        return enabled
                ? ConditionOutcome.match(message.found("enabled").items(Boolean.TRUE))
                : ConditionOutcome.noMatch(message.didNotFind("enabled").items(Boolean.FALSE));
    }
}
