package com.acme.framework.web.interceptor;

import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.web.config.ClientContextInvalidPolicy;
import com.acme.framework.web.config.WebProperties;
import com.acme.framework.web.support.ClientContextParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 头提取过滤器测试（FR-007：REJECT 策略、finally 清理，BR-025、5.6）。
 */
class ClientContextExtractFilterTest {

    private static ClientContextExtractFilter filter(ClientContextInvalidPolicy policy) {
        WebProperties properties = new WebProperties();
        properties.getClientContext().setInvalidPolicy(policy);
        return new ClientContextExtractFilter(new ClientContextParser(properties));
    }

    private static FilterChain recordingChain(AtomicBoolean invoked) {
        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                invoked.set(true);
                request.setAttribute(AuthenticatedSubject.ATTRIBUTE_NAME,
                        new AuthenticatedSubject("u-1", "t-1", null, null));
            }
        };
    }

    /** 正常请求：解析暂存 + finally 清理（BR-025）。 */
    @Test
    void finallyClearsStagedAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Acme-Channel", "B");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();
        AtomicBoolean sawDraftInChain = new AtomicBoolean();

        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                chainInvoked.set(true);
                sawDraftInChain.set(req.getAttribute(ClientContextDraft.ATTRIBUTE_NAME) instanceof ClientContextDraft);
                req.setAttribute(AuthenticatedSubject.ATTRIBUTE_NAME,
                        new AuthenticatedSubject("u-1", "t-1", null, null));
            }
        };

        filter(ClientContextInvalidPolicy.IGNORE).doFilter(request, response, chain);

        assertThat(chainInvoked).isTrue();
        assertThat(sawDraftInChain).isTrue();
        assertThat(request.getAttribute(ClientContextDraft.ATTRIBUTE_NAME)).isNull();
        assertThat(request.getAttribute(AuthenticatedSubject.ATTRIBUTE_NAME)).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** REJECT 策略：端别声明非法直接拒绝，不进链路（FR-007、5.4）。 */
    @Test
    void rejectPolicyStopsChainWithBadRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Acme-Channel", "illegal-channel");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter(ClientContextInvalidPolicy.REJECT).doFilter(request, response, recordingChain(chainInvoked));

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"success\":false");
    }

    /** IGNORE 策略：非法端别丢弃字段仍进链路（5.4）。 */
    @Test
    void ignorePolicyDropsInvalidChannelAndContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Acme-Channel", "illegal-channel");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter(ClientContextInvalidPolicy.IGNORE).doFilter(request, response, recordingChain(chainInvoked));

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
