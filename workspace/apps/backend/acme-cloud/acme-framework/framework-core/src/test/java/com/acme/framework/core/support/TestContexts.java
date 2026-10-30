package com.acme.framework.core.support;

import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试夹具：Modules.md 2.1 五类上下文预置与逐项断言（AC-001）。
 */
final class TestContexts {

    private TestContexts() {
    }

    static void bind(String marker, Locale locale) {
        UserContext.bind(new UserContext("user-" + marker, Set.of("role-" + marker)));
        TenantContext.bind(new TenantContext("tenant-" + marker));
        TraceContext.bind(new TraceContext("trace-" + marker, "span-" + marker));
        LocaleContext.bind(new LocaleContext(locale));
        ClientContext.bind(new ClientContext(Channel.C, "device-" + marker, "app-" + marker));
    }

    static void assertContext(String marker, Locale locale) {
        UserContext user = UserContext.get();
        assertThat(user).isNotNull();
        assertThat(user.userId()).isEqualTo("user-" + marker);
        assertThat(user.roles()).containsExactly("role-" + marker);
        assertThat(TenantContext.get()).isNotNull();
        assertThat(TenantContext.get().tenantId()).isEqualTo("tenant-" + marker);
        assertThat(TraceContext.get()).isNotNull();
        assertThat(TraceContext.get().traceId()).isEqualTo("trace-" + marker);
        assertThat(TraceContext.get().spanId()).isEqualTo("span-" + marker);
        assertThat(LocaleContext.get()).isNotNull();
        assertThat(LocaleContext.get().locale()).isEqualTo(locale);
        assertThat(ClientContext.get()).isNotNull();
        assertThat(ClientContext.get().channel()).isEqualTo(Channel.C);
        assertThat(ClientContext.get().deviceId()).isEqualTo("device-" + marker);
        assertThat(ClientContext.get().appVersion()).isEqualTo("app-" + marker);
    }

    static void assertCleared() {
        assertThat(UserContext.get()).isNull();
        assertThat(TenantContext.get()).isNull();
        assertThat(TraceContext.get()).isNull();
        assertThat(LocaleContext.get()).isNull();
        assertThat(ClientContext.get()).isNull();
    }
}
