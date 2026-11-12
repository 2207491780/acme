package com.acme.framework.feign.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.ClientContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.feign.config.FeignProperties;
import com.acme.framework.feign.support.FeignError;
import com.acme.framework.security.support.InternalTokenIssuer;
import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 内部令牌注入（FR-022/AC-022、BR-013）：复用 framework-security JWS HS256 协议（Q-D001 签发归位），
 * 注入 {@code X-Acme-Token}；签发失败显式失败、不发无令牌调用（FR-022 异常分支）。
 */
public class InternalTokenRequestInterceptor implements RequestInterceptor {

    private final Supplier<InternalTokenIssuer> issuer;
    private final FeignProperties properties;

    public InternalTokenRequestInterceptor(Supplier<InternalTokenIssuer> issuer, FeignProperties properties) {
        this.issuer = issuer;
        this.properties = properties;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (!properties.getToken().isEnabled()) {
            return;
        }
        String token;
        try {
            token = issuer.get().issue(subject());
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException(FeignError.TOKEN_ISSUE_FAILED, "内部令牌签发失败", ex);
        }
        if (token == null || token.isBlank()) {
            throw new BizException(FeignError.TOKEN_ISSUE_FAILED, "内部令牌签发为空");
        }
        template.header(ServiceHeaders.SERVICE_TOKEN, token);
    }

    private static AuthenticatedSubject subject() {
        UserContext user = UserContext.get();
        TenantContext tenant = TenantContext.get();
        ClientContext client = ClientContext.get();
        String userId = user == null ? null : user.userId();
        String tenantId = tenant == null ? null : tenant.tenantId();
        Set<String> roles = user == null ? Set.of() : user.roles();
        var channel = client == null ? null : client.channel();
        return new AuthenticatedSubject(userId, tenantId, channel, roles);
    }
}
