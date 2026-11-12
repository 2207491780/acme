package com.acme.framework.trace.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import com.acme.framework.trace.support.Traces;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP 通道入口组件测试（FR-002；AC-002/003/004/016）。
 */
class TraceHttpFilterTest {

    private final TraceHttpFilter filter = new TraceHttpFilter();

    @AfterEach
    void tearDown() {
        Traces.clear();
    }

    /** AC-002：入站复用 + 处理期 MDC 同值 + 出站同值头。 */
    @Test
    void reusesInboundValuesAndEchoesOnResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceConstants.TRACE_ID_HEADER, "t-in");
        request.addHeader(TraceConstants.SPAN_ID_HEADER, "s-in");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] duringHandle = new String[2];

        filter.doFilter(request, response, recordingChain(duringHandle));

        assertThat(duringHandle[0]).isEqualTo("t-in");
        assertThat(duringHandle[1]).isEqualTo("s-in");
        assertThat(response.getHeader(TraceConstants.TRACE_ID_HEADER)).isEqualTo("t-in");
        assertThat(response.getHeader(TraceConstants.SPAN_ID_HEADER)).isEqualTo("s-in");
        assertNoResidue();
    }

    /** AC-003：无入站生成新值 + 出站携带该值。 */
    @Test
    void generatesWhenHeadersMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] duringHandle = new String[2];

        filter.doFilter(request, response, recordingChain(duringHandle));

        assertThat(duringHandle[0]).hasSize(32);
        assertThat(duringHandle[1]).hasSize(16);
        assertThat(response.getHeader(TraceConstants.TRACE_ID_HEADER)).isEqualTo(duringHandle[0]);
        assertThat(response.getHeader(TraceConstants.SPAN_ID_HEADER)).isEqualTo(duringHandle[1]);
        assertNoResidue();
    }

    /** 两头各自独立复用/生成（DQ-001）：单头缺失只补缺失头。 */
    @Test
    void missingEndsResolvedIndependently() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceConstants.TRACE_ID_HEADER, "t-only");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] duringHandle = new String[2];

        filter.doFilter(request, response, recordingChain(duringHandle));

        assertThat(duringHandle[0]).isEqualTo("t-only");
        assertThat(duringHandle[1]).hasSize(16);
    }

    /** AC-004：抛出异常路径后无残留；出站响应仍携带同值头（FR-002 异常分支）。 */
    @Test
    void clearsAfterExceptionPath() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceConstants.TRACE_ID_HEADER, "t-err");
        request.addHeader(TraceConstants.SPAN_ID_HEADER, "s-err");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain()))
                .isInstanceOf(ServletException.class);

        assertThat(response.getHeader(TraceConstants.TRACE_ID_HEADER)).isEqualTo("t-err");
        assertThat(response.getHeader(TraceConstants.SPAN_ID_HEADER)).isEqualTo("s-err");
        assertNoResidue();
    }

    /** AC-016：仅处理 trace 头——不读取/改写身份与上下文头（Modules.md 2.4.1 解释边界）。 */
    @Test
    void onlyHandlesTraceHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceConstants.TRACE_ID_HEADER, "t-b");
        request.addHeader(TraceConstants.SPAN_ID_HEADER, "s-b");
        request.addHeader("X-User-Id", "u-1");
        request.addHeader("Authorization", "Bearer x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] duringHandle = new String[2];

        filter.doFilter(request, response, recordingChain(duringHandle));

        assertThat(request.getHeader("X-User-Id")).isEqualTo("u-1");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer x");
        assertThat(response.getHeader("X-User-Id")).isNull();
        assertThat(response.getHeader("Authorization")).isNull();
        assertThat(response.getHeaderNames())
                .containsExactlyInAnyOrder(TraceConstants.TRACE_ID_HEADER, TraceConstants.SPAN_ID_HEADER);
    }

    // ---------- 辅助 ----------

    private static FilterChain recordingChain(String[] duringHandle) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                duringHandle[0] = MDC.get(TraceConstants.MDC_TRACE_ID);
                duringHandle[1] = MDC.get(TraceConstants.MDC_SPAN_ID);
                TraceContext current = Traces.current();
                assertThat(current).isNotNull();
                assertThat(current.traceId()).isEqualTo(duringHandle[0]);
            }
        };
    }

    private static FilterChain throwingChain() {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) throws ServletException, IOException {
                throw new ServletException("业务异常");
            }
        };
    }

    private static void assertNoResidue() {
        assertThat(Traces.current()).isNull();
        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceConstants.MDC_SPAN_ID)).isNull();
    }
}
