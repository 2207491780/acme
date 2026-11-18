package com.acme.framework.jackson.internal;

import com.acme.framework.jackson.support.AcmeJacksonComponent;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.core.annotation.AnnotationUtils;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code @AcmeJacksonComponent} 组件 → 注册面（FR-008）：汇总为一个 Module 供 customizer 注册。
 * 三类组件（DQ-007）：JacksonModule → 透传 setupModule；ValueSerializer/ValueDeserializer →
 * 按 {@link AcmeJacksonComponent#value()} 声明的目标类型注册且必填；标注组件非法随容器默认失败行为启动失败
 * （FR-008 异常分支，不另设异常体系）。注解经 ultimateTargetClass 解析，CGLIB/JDK 代理不失效。
 */
final class JacksonComponentModules {

    private JacksonComponentModules() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static JacksonModule of(Collection<Object> components) {
        List<JacksonModule> modules = new ArrayList<>();
        SimpleModule aggregated = new SimpleModule("AcmeJacksonComponents") {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                for (JacksonModule module : modules) {
                    module.setupModule(context);
                }
            }
        };
        for (Object component : components) {
            Class<?> targetClass = AopProxyUtils.ultimateTargetClass(component);
            AcmeJacksonComponent annotation =
                    AnnotationUtils.findAnnotation(targetClass, AcmeJacksonComponent.class);
            if (component instanceof JacksonModule module) {
                modules.add(module);
                continue;
            }
            Class<?>[] targets = annotation != null ? annotation.value() : new Class<?>[0];
            if (component instanceof ValueSerializer<?> serializer) {
                requireTargets(targetClass, targets);
                for (Class<?> target : targets) {
                    aggregated.addSerializer((Class) target, (ValueSerializer) serializer);
                }
                continue;
            }
            if (component instanceof ValueDeserializer<?> deserializer) {
                requireTargets(targetClass, targets);
                for (Class<?> target : targets) {
                    aggregated.addDeserializer((Class) target, (ValueDeserializer) deserializer);
                }
                continue;
            }
            throw new IllegalArgumentException(
                    "@AcmeJacksonComponent 仅可标注 JacksonModule/ValueSerializer/ValueDeserializer（DQ-007）："
                            + targetClass.getName());
        }
        return aggregated;
    }

    private static void requireTargets(Class<?> targetClass, Class<?>[] targets) {
        if (targets.length == 0) {
            throw new IllegalArgumentException(
                    "ValueSerializer/ValueDeserializer 组件须经 value() 声明目标类型（DQ-007）："
                            + targetClass.getName());
        }
    }
}
