package com.acme.framework.doc.internal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * acme.doc.* → springdoc.* 桥接（FR-015、NFR-004）：路径可配、开关关闭后端点不可访问；
 * 用户显式设置的 springdoc.* 优先（不覆盖）。
 */
public class DocEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE = "acmeDocBridge";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> bridge = new HashMap<>();
        putIfAbsent(environment, bridge, "springdoc.api-docs.path", "acme.doc.api-docs-path");
        putIfAbsent(environment, bridge, "springdoc.swagger-ui.path", "acme.doc.swagger-ui-path");
        boolean enabled = environment.getProperty("acme.doc.enabled", Boolean.class, Boolean.TRUE);
        if (!enabled) {
            putIfAbsent(environment, bridge, "springdoc.api-docs.enabled", "acme.doc.enabled");
            putIfAbsent(environment, bridge, "springdoc.swagger-ui.enabled", "acme.doc.enabled");
        }
        if (!bridge.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE, bridge));
        }
    }

    /** 仅当目标键未被用户显式设置且来源键有值时桥接。 */
    private static void putIfAbsent(ConfigurableEnvironment environment, Map<String, Object> bridge,
                                    String targetKey, String sourceKey) {
        if (environment.getProperty(targetKey) != null) {
            return;
        }
        String value = environment.getProperty(sourceKey);
        if (value != null) {
            bridge.put(targetKey, value);
        }
    }
}
