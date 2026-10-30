package com.acme.platform.infra.security;

import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * infra 权限拦截器测试：注解声明 → 远程判定；无注解放行。
 */
class RequirePermissionInterceptorTest {

    private final AuthorizationGateway gateway = mock(AuthorizationGateway.class);
    private final RequirePermissionInterceptor interceptor = new RequirePermissionInterceptor(providerOf(gateway));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AuthorizationGateway> providerOf(AuthorizationGateway gateway) {
        ObjectProvider<AuthorizationGateway> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(gateway);
        return provider;
    }

    @Test
    void annotatedHandlerTriggersRemoteDecision() throws Exception {
        HandlerMethod handler = new HandlerMethod(new SampleController(), "annotated");
        interceptor.preHandle(mock(HttpServletRequest.class), mock(HttpServletResponse.class), handler);
        verify(gateway).requirePermission("infra:audit:list");
    }

    @Test
    void unannotatedHandlerPassesThrough() throws Exception {
        HandlerMethod handler = new HandlerMethod(new SampleController(), "plain");
        interceptor.preHandle(mock(HttpServletRequest.class), mock(HttpServletResponse.class), handler);
        verify(gateway, never()).requirePermission(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonHandlerMethodPassesThrough() throws Exception {
        interceptor.preHandle(mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object());
        verify(gateway, never()).requirePermission(org.mockito.ArgumentMatchers.anyString());
    }

    static class SampleController {

        @RequirePermission("infra:audit:list")
        public String annotated() {
            return "ok";
        }

        public String plain() {
            return "ok";
        }
    }
}
