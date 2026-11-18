package com.acme.framework.audit.aspect;

import com.acme.framework.audit.config.AuditProperties;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.AccessException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * beforeQuery 旧值快照序列化面补充测试（REQ-20261117-061017 确认③）：
 * Jackson 可用走 JSON、缺 Jackson 回退 toString、无 beanResolver 时 @bean 静默、
 * 求值异常不阻断业务。核心分支由 {@code REQ20261117061017AuditBeforeQueryTest} 覆盖。
 */
class AuditBeforeQuerySerializationTest {

    private final List<AuditEvent> events = new ArrayList<>();

    private <T> T proxy(T target, BeanResolver resolver, JsonMapper jsonMapper) {
        AuditAspect aspect = new AuditAspect(events::add, new AuditProperties(), resolver, jsonMapper);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    /** Jackson 可用：before 摘要为 JSON 形态（确认③ JSON 优先）。 */
    @Test
    void should_serializeBeforeSnapshotAsJson_when_jacksonAvailable() {
        SnapshotBean resolver = new SnapshotBean("old-state-value");
        BeanResolver beanResolver = (context, beanName) ->
                "snapshotBean".equals(beanName) ? resolver : null;
        Sample proxy = proxy(new SampleImpl(), beanResolver, JsonMapper.builder().build());

        assertThat(proxy.update("42")).isEqualTo("done");

        assertThat(events).hasSize(1);
        String content = events.getFirst().content();
        assertThat(content).startsWith("before=");
        assertThat(content).as("Jackson 序列化形态（JSON 带引号）").contains("\"old-state-value\"");
        assertThat(content).as("参数摘要随后（确认③合并形态）").contains("id=42");
    }

    /** 缺 Jackson（null mapper）：回退 toString 形态（确认③ toString 回退）。 */
    @Test
    void should_fallBackToToString_when_jacksonMissing() {
        SnapshotBean resolver = new SnapshotBean("old-state-value");
        BeanResolver beanResolver = (context, beanName) ->
                "snapshotBean".equals(beanName) ? resolver : null;
        Sample proxy = proxy(new SampleImpl(), beanResolver, null);

        assertThat(proxy.update("42")).isEqualTo("done");

        assertThat(events.getFirst().content())
                .startsWith("before=")
                .as("toString 形态（无 JSON 引号）")
                .contains("old-state-value")
                .doesNotContain("\"old-state-value\"");
    }

    /** 无 beanResolver（两参构造）：@beanName 求值静默失败，content 不产 before 片段。 */
    @Test
    void should_omitBeforeSilently_when_noBeanResolver() {
        AuditAspect aspect = new AuditAspect(events::add, new AuditProperties());
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleImpl());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        Sample proxy = factory.getProxy();

        assertThat(proxy.update("42")).as("求值失败不阻断业务").isEqualTo("done");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().content())
                .as("before 片段缺失（@beanName 静默失败），参数摘要仍在").isEqualTo("id=42");
    }

    /** resolver 抛出异常（AccessException 之外的运行时异常）同样静默。 */
    @Test
    void should_omitBeforeSilently_when_resolverThrows() {
        BeanResolver beanResolver = new BeanResolver() {
            @Override
            public Object resolve(EvaluationContext context, String beanName) throws AccessException {
                throw new IllegalStateException("resolver-broken");
            }
        };
        Sample proxy = proxy(new SampleImpl(), beanResolver, null);
        assertThat(proxy.update("42")).isEqualTo("done");
        assertThat(events.getFirst().content()).isEqualTo("id=42");
    }

    // ---------- 被测面 ----------

    interface Sample {
        String update(String id);
    }

    static class SampleImpl implements Sample {
        @Override
        @AuditLog(action = "demo.snapshot.update", targetId = "#p0",
                beforeQuery = "@snapshotBean.load(#p0)", contentParams = {"id"})
        public String update(String id) {
            return "done";
        }
    }

    /** 旧值快照桩 bean（load 须 public——SpEL ReflectiveMethodResolver 仅解析 public 方法）。 */
    static final class SnapshotBean {
        private final String value;

        SnapshotBean(String value) {
            this.value = value;
        }

        public String load(String id) {
            return value;
        }
    }
}
