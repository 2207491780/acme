package com.acme.framework.core.support;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.ClientContext;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上下文 → {@link ServiceHeaders} 出站约定头映射（Modules.md 4.3 下沉共享）：
 * 由 framework-feign（FR-023）/ framework-stream（FR-013）取值注入，缺值不注入。
 * 纯逻辑、无 IO。租户头取 {@link TenantContext}（租户唯一事实源，DP-006）。
 *
 * <p>反向恢复（头 → 上下文写入）属受信写入面，仅存在于 framework-stream 消费管线内部
 * （Modules.md 2.4.4/3.1.2），本类不出恢复 API，防止 HTTP 路径自注入身份（M-10）。</p>
 */
public final class ContextHeaders {

    private ContextHeaders() {
    }

    /**
     * 出站约定头（缺值不注入，FR-023/FR-032 异常分支）：操作人头取 {@code UserContext.userId()} 同源；
     * 租户头取 {@code TenantContext}（数据域租户）；语言头取 {@code LocaleContext}（11.6.3 透传，值为 language tag）。
     */
    public static Map<String, String> outbound() {
        Map<String, String> headers = new LinkedHashMap<>();
        UserContext user = UserContext.get();
        if (user != null) {
            if (hasText(user.userId())) {
                headers.put(ServiceHeaders.USER_ID, user.userId());
                headers.put(ServiceHeaders.OPERATOR, user.userId());
            }
        }
        TenantContext tenant = TenantContext.get();
        if (tenant != null && hasText(tenant.tenantId())) {
            headers.put(ServiceHeaders.TENANT_ID, tenant.tenantId());
        }
        ClientContext client = ClientContext.get();
        if (client != null) {
            if (client.channel() != null) {
                headers.put(ServiceHeaders.CHANNEL, client.channel().name());
            }
            if (hasText(client.deviceId())) {
                headers.put(ServiceHeaders.DEVICE_ID, client.deviceId());
            }
            if (hasText(client.appVersion())) {
                headers.put(ServiceHeaders.APP_VERSION, client.appVersion());
            }
        }
        LocaleContext locale = LocaleContext.get();
        if (locale != null && locale.locale() != null) {
            headers.put(ServiceHeaders.LOCALE, locale.locale().toLanguageTag());
        }
        return headers;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
