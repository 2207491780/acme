package com.acme.framework.core.support;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.framework.core.internal.ApplicationContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用上下文访问工具测试（AC-018/AC-019；BR-021/BR-022、DQ-002/DQ-003）。
 */
class ApplicationContextsTest {

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        ApplicationContextRegistry.clear();
        context = new AnnotationConfigApplicationContext();
        context.registerBean("demoComponent", DemoComponent.class);
        context.registerBean(ApplicationContextRegistry.class);
        Map<String, Object> properties = Map.of("app.demo-key", "demo-value");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        ApplicationContextRegistry.clear();
        context.close();
    }

    // ---------- AC-018 ----------

    @Test
    void getBeanByTypeReturnsAssembledComponent() {
        DemoComponent component = ApplicationContexts.getBean(DemoComponent.class);
        assertThat(component).isNotNull();
        assertThat(component.ping()).isEqualTo("pong");
    }

    @Test
    void getBeanByNameAndTypeReturnsAssembledComponent() {
        DemoComponent component = ApplicationContexts.getBean("demoComponent", DemoComponent.class);
        assertThat(component).isNotNull();
    }

    @Test
    void getBeanByNameReturnsAssembledComponent() {
        Object component = ApplicationContexts.getBean("demoComponent");
        assertThat(component).isInstanceOf(DemoComponent.class);
    }

    @Test
    void getPropertyReadsOptionalValue() {
        assertThat(ApplicationContexts.getProperty("app.demo-key")).isEqualTo("demo-value");
        assertThat(ApplicationContexts.getProperty("app.missing-key")).isNull();
    }

    @Test
    void getRequiredPropertyReadsValue() {
        assertThat(ApplicationContexts.getRequiredProperty("app.demo-key")).isEqualTo("demo-value");
    }

    @Test
    void getRequiredPropertyMissingKeyThrows() {
        assertThatThrownBy(() -> ApplicationContexts.getRequiredProperty("app.missing-key"))
                .isInstanceOf(BizException.class)
                .hasMessage("配置项缺失")
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(CommonError.NOT_FOUND);
    }

    // ---------- AC-019 ----------

    @Test
    void missingBeanThrowsExplicitException() {
        assertThatThrownBy(() -> ApplicationContexts.getBean(Runnable.class))
                .isInstanceOf(BizException.class)
                .hasMessage("组件不存在")
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(CommonError.NOT_FOUND);
        assertThatThrownBy(() -> ApplicationContexts.getBean("no-such-bean", DemoComponent.class))
                .isInstanceOf(BizException.class)
                .hasMessage("组件不存在")
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(CommonError.NOT_FOUND);
        assertThatThrownBy(() -> ApplicationContexts.getBean("no-such-bean"))
                .isInstanceOf(BizException.class)
                .hasMessage("组件不存在")
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(CommonError.NOT_FOUND);
    }

    /** 对外消息不得携带 bean FQCN / 配置键（10.5.4）。 */
    @Test
    void exceptionMessagesDoNotLeakImplementationDetails() {
        assertThatThrownBy(() -> ApplicationContexts.getBean(Runnable.class))
                .hasMessageNotContaining("java.lang.Runnable")
                .hasMessageNotContaining("com.acme");
        assertThatThrownBy(() -> ApplicationContexts.getRequiredProperty("app.missing-key"))
                .hasMessageNotContaining("app.missing-key");
    }

    @Test
    void contextNotReadyThrowsExplicitException() {
        ApplicationContextRegistry.clear();
        assertThatThrownBy(() -> ApplicationContexts.getBean(DemoComponent.class))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(CommonError.INTERNAL_ERROR);
        assertThatThrownBy(() -> ApplicationContexts.getProperty("app.demo-key"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(CommonError.INTERNAL_ERROR);
        assertThatThrownBy(() -> ApplicationContexts.getRequiredProperty("app.demo-key"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(CommonError.INTERNAL_ERROR);
    }

    static class DemoComponent {
        String ping() {
            return "pong";
        }
    }
}
