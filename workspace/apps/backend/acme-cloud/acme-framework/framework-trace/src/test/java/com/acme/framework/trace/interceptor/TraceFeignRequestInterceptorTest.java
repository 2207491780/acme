package com.acme.framework.trace.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import com.acme.framework.trace.support.Traces;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 发送侧拦截测试（FR-003、AC-005 发送侧；DQ-003 生成值仅携带不绑定）。
 */
class TraceFeignRequestInterceptorTest {

    private final TraceFeignRequestInterceptor interceptor = new TraceFeignRequestInterceptor();

    @AfterEach
    void tearDown() {
        Traces.clear();
    }

    /** 有上下文：携带同值。 */
    @Test
    void carriesCurrentContextValues() {
        Traces.bind(new TraceContext("t-feign", "s-feign"));
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(headerValues(template, TraceConstants.TRACE_ID_HEADER)).containsExactly("t-feign");
        assertThat(headerValues(template, TraceConstants.SPAN_ID_HEADER)).containsExactly("s-feign");
    }

    /** 无上下文：生成后仅携带、不绑定调用方线程（DQ-003）。 */
    @Test
    void generatesAndDoesNotBindWhenNoContext() {
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        String traceId = singleHeader(template, TraceConstants.TRACE_ID_HEADER);
        String spanId = singleHeader(template, TraceConstants.SPAN_ID_HEADER);
        assertThat(traceId).hasSize(32);
        assertThat(spanId).hasSize(16);
        assertThat(Traces.current()).isNull();
    }

    /** 调用失败/超时不清理调用方上下文（FR-003 异常分支）：拦截器不触碰调用方绑定。 */
    @Test
    void keepsCallerContextUntouched() {
        Traces.bind(new TraceContext("t-keep", "s-keep"));
        interceptor.apply(new RequestTemplate());
        assertThat(Traces.current().traceId()).isEqualTo("t-keep");
    }

    private static Collection<String> headerValues(RequestTemplate template, String name) {
        return template.headers().get(name);
    }

    private static String singleHeader(RequestTemplate template, String name) {
        Collection<String> values = headerValues(template, name);
        assertThat(values).hasSize(1);
        return values.iterator().next();
    }
}
