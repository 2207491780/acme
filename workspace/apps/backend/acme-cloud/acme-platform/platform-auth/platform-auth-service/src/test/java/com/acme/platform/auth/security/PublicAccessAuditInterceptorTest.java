package com.acme.platform.auth.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.common.context.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.UnsupportedEncodingException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公开端点访问审计拦截器测试（REQ-20261125-001736 AC-1）：logger acme.security.public-access、
 * 字段序 traceId/clientIp/endpoint/tenantCode/ua、缺失填 -、仅 GET 三路径、响应零变化。
 */
class PublicAccessAuditInterceptorTest {

    private final PublicAccessAuditInterceptor interceptor = new PublicAccessAuditInterceptor();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanup() {
        TraceContext.clear();
        Logger logger = (Logger) LoggerFactory.getLogger(PublicAccessAuditInterceptor.AUDIT_LOGGER_NAME);
        logger.detachAppender(appender);
    }

    private void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(PublicAccessAuditInterceptor.AUDIT_LOGGER_NAME);
        appender.list.clear();
        appender.start();
        logger.addAppender(appender);
    }

    @Test
    void should_logAllFiveFieldsInOrder_when_getRequestOnAuditedPath() {
        attachAppender();
        TraceContext.bind(new TraceContext("trace-pub-1", "span-1"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/auth/tenants");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0)");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(appender.list.get(0).getLoggerName()).isEqualTo("acme.security.public-access");
        // 字段序：traceId/clientIp/endpoint/tenantCode/ua
        int traceAt = message.indexOf("traceId=trace-pub-1");
        int ipAt = message.indexOf("clientIp=203.0.113.9");
        int endpointAt = message.indexOf("endpoint=/admin/v1/auth/tenants");
        int tenantAt = message.indexOf("tenantCode=-");
        int uaAt = message.indexOf("ua=Mozilla/5.0 (Windows NT 10.0)");
        assertThat(traceAt).isGreaterThan(-1);
        assertThat(ipAt).isGreaterThan(traceAt);
        assertThat(endpointAt).isGreaterThan(ipAt);
        assertThat(tenantAt).isGreaterThan(endpointAt);
        assertThat(uaAt).isGreaterThan(tenantAt);
    }

    @Test
    void should_logDeclaredTenantCode_when_paramPresent() {
        attachAppender();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/auth/captcha-policy");
        request.setParameter("tenantCode", " acme-demo ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("tenantCode=acme-demo");
    }

    @Test
    void should_fillPlaceholder_when_fieldsMissing() {
        attachAppender();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/auth/public-config");
        request.setRemoteAddr("192.168.1.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        // 缺失填 -：traceId 未绑定、tenantCode 无参、UA 无头；clientIp 回退 remoteAddr
        assertThat(message).contains("traceId=-").contains("clientIp=192.168.1.10")
                .contains("tenantCode=-").contains("ua=-");
    }

    @Test
    void should_notLog_when_methodIsNotGet() {
        attachAppender();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/auth/tenants");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void should_notLog_when_pathNotAudited() {
        attachAppender();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/auth/login-logs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void should_keepResponseUntouched_when_auditRuns() throws UnsupportedEncodingException {
        attachAppender();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/auth/tenants");
        request.addHeader("User-Agent", "probe/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        // 响应与行为零变化（AC-1）：未写状态/未写体
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
