package com.acme.framework.web.interceptor;

import com.acme.framework.core.support.ClientContextDraft;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访问日志过滤器冒烟（FR-006：日志失败不影响请求处理、暂存位快照仍可取）。
 */
class AccessLogFilterTest {

    /** 冒烟：链路执行、状态与耗时可观测、不抛异常。 */
    @Test
    void smokeLogsWithoutBreakingChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                chainInvoked.set(true);
                req.setAttribute(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "device-1", "1.0"));
                ((MockHttpServletResponse) res).setStatus(201);
            }
        };

        new AccessLogFilter("demo-service").doFilter(request, response, chain);

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(201);
    }

    /** 暂存位清理后仍可经快照取到（FR-006 要素完整、BR-025）。 */
    @Test
    void snapshotKeepsStagedAttributeAfterCleanup() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean draftVisibleAfterClean = new AtomicBoolean();
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                req.setAttribute(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "device-1", "1.0"));
                req.removeAttribute(ClientContextDraft.ATTRIBUTE_NAME);
                draftVisibleAfterClean.set(req.getAttribute(ClientContextDraft.ATTRIBUTE_NAME) != null);
            }
        };

        new AccessLogFilter("demo-service").doFilter(request, response, chain);

        assertThat(draftVisibleAfterClean).isTrue();
    }
}
