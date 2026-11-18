package com.acme.framework.trace.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.framework.trace.support.Traces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * web 测试环境 HTTP 入口集成测试（AC-001/002/003/004；Q-001 web 测试环境口径）。
 */
class TraceHttpWebIntegrationTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TraceController())
            .addFilters(new TraceHttpFilter())
            .build();

    @AfterEach
    void tearDown() {
        Traces.clear();
    }

    /** AC-001（P2 验收一）：HTTP 请求处理期间 MDC 含 traceId 与 spanId，出站同值。 */
    @Test
    void mdcContainsTraceDuringRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/trace-probe")).andReturn();
        MockHttpServletResponse response = result.getResponse();
        String mdcTrace = response.getHeader("X-Mdc-Trace");
        String mdcSpan = response.getHeader("X-Mdc-Span");
        assertThat(mdcTrace).hasSize(32);
        assertThat(mdcSpan).hasSize(16);
        assertThat(response.getHeader(TraceConstants.TRACE_ID_HEADER)).isEqualTo(mdcTrace);
        assertThat(response.getHeader(TraceConstants.SPAN_ID_HEADER)).isEqualTo(mdcSpan);
        assertThat(Traces.current()).isNull();
        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isNull();
    }

    /** AC-002：入站复用、处理期 MDC 与出站响应头同值。 */
    @Test
    void reusesInboundTraceHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get("/trace-probe")
                        .header(TraceConstants.TRACE_ID_HEADER, "t-http")
                        .header(TraceConstants.SPAN_ID_HEADER, "s-http"))
                .andReturn();
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getHeader("X-Mdc-Trace")).isEqualTo("t-http");
        assertThat(response.getHeader("X-Mdc-Span")).isEqualTo("s-http");
        assertThat(response.getHeader(TraceConstants.TRACE_ID_HEADER)).isEqualTo("t-http");
        assertThat(response.getHeader(TraceConstants.SPAN_ID_HEADER)).isEqualTo("s-http");
        assertThat(Traces.current()).isNull();
    }

    @Controller
    static class TraceController {

        /** 处理期把 MDC 值回显到响应头，供断言（不落业务语义）。 */
        @GetMapping("/trace-probe")
        @ResponseBody
        String probe(MockHttpServletResponse response) {
            response.setHeader("X-Mdc-Trace", MDC.get(TraceConstants.MDC_TRACE_ID));
            response.setHeader("X-Mdc-Span", MDC.get(TraceConstants.MDC_SPAN_ID));
            return "ok";
        }
    }
}
