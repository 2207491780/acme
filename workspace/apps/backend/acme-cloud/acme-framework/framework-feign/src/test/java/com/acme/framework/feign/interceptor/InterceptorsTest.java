package com.acme.framework.feign.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.feign.config.FeignProperties;
import com.acme.framework.feign.support.FeignError;
import com.acme.framework.security.support.InternalTokenIssuer;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内部令牌注入与上下文头注入（FR-022/023、AC-022/023；BR-014 缺值不注入）。
 */
class InterceptorsTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
        ClientContext.clear();
    }

    @Test
    void injectsServiceTokenFromIssuer() {
        AtomicReference<AuthenticatedSubject> seen = new AtomicReference<>();
        InternalTokenIssuer issuer = subject -> {
            seen.set(subject);
            return "token-1";
        };
        UserContext.bind(new UserContext("u-1", Set.of("admin")));
        TenantContext.bind(new TenantContext("t-1"));
        RequestTemplate template = new RequestTemplate();
        new InternalTokenRequestInterceptor(() -> issuer, new FeignProperties()).apply(template);
        assertThat(template.headers().get(ServiceHeaders.SERVICE_TOKEN)).containsExactly("token-1");
        assertThat(seen.get().userId()).isEqualTo("u-1");
        assertThat(seen.get().tenantId()).isEqualTo("t-1");
    }

    @Test
    void tokenIssueFailureFailsExplicitly() {
        InternalTokenIssuer issuer = subject -> {
            throw new IllegalStateException("no key");
        };
        RequestTemplate template = new RequestTemplate();
        assertThatThrownBy(() -> new InternalTokenRequestInterceptor(() -> issuer, new FeignProperties()).apply(template))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(FeignError.TOKEN_ISSUE_FAILED));
    }

    @Test
    void emptyTokenRejected() {
        InternalTokenIssuer issuer = subject -> " ";
        RequestTemplate template = new RequestTemplate();
        assertThatThrownBy(() -> new InternalTokenRequestInterceptor(() -> issuer, new FeignProperties()).apply(template))
                .isInstanceOf(BizException.class);
    }

    @Test
    void tokenInjectionCanBeDisabled() {
        FeignProperties properties = new FeignProperties();
        properties.getToken().setEnabled(false);
        RequestTemplate template = new RequestTemplate();
        new InternalTokenRequestInterceptor(() -> subject -> "token-1", properties).apply(template);
        assertThat(template.headers()).doesNotContainKey(ServiceHeaders.SERVICE_TOKEN);
    }

    @Test
    void contextHeadersInjected() {
        UserContext.bind(new UserContext("u-1", Set.of()));
        TenantContext.bind(new TenantContext("t-1"));
        ClientContext.bind(new ClientContext(Channel.C, "d-1", "2.0"));
        RequestTemplate template = new RequestTemplate();
        new ContextHeaderRequestInterceptor().apply(template);
        assertThat(template.headers().get(ServiceHeaders.USER_ID)).containsExactly("u-1");
        assertThat(template.headers().get(ServiceHeaders.OPERATOR)).containsExactly("u-1");
        assertThat(template.headers().get(ServiceHeaders.TENANT_ID)).containsExactly("t-1");
        assertThat(template.headers().get(ServiceHeaders.CHANNEL)).containsExactly("C");
        assertThat(template.headers().get(ServiceHeaders.DEVICE_ID)).containsExactly("d-1");
    }

    @Test
    void missingContextValuesNotInjected() {
        RequestTemplate template = new RequestTemplate();
        new ContextHeaderRequestInterceptor().apply(template);
        assertThat(template.headers()).isEmpty();
    }
}
