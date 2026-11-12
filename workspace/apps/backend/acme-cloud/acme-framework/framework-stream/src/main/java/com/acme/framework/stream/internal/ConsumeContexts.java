package com.acme.framework.stream.internal;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.ContextScope;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;

import java.util.Locale;
import java.util.Map;

/**
 * 消费管线上下文恢复（Modules.md 2.4.4/3.1.2 受信写入面）：依据 {@code ServiceHeaders} 约定头恢复
 * UserContext / TenantContext / ClientContext，返回的 {@link ContextScope} 负责 finally 清理（2.4.3）。
 * 仅供 {@link com.acme.framework.stream.interceptor.ConsumeChannelInterceptor} 调用；
 * 信任边界为内部 MQ 基础设施（13.2），不得用于 HTTP 路径（M-10）。实现细节（Modules.md 3.5 internal，模块外不得引用）。
 */
public final class ConsumeContexts {

    private ConsumeContexts() {
    }

    /**
     * 恢复上下文：约定头缺失或非法不写入非法值（按缺失口径处理）；
     * 调用方须 try-with-resources 关闭返回作用域，保证异常路径同样清理无残留。
     */
    public static ContextScope restore(Map<String, ?> headers) {
        ContextScope scope = ContextScope.open();
        if (headers == null) {
            return scope;
        }
        String userId = text(headers, ServiceHeaders.USER_ID);
        String tenantId = text(headers, ServiceHeaders.TENANT_ID);
        if (userId != null) {
            UserContext.bind(new UserContext(userId, null));
        }
        if (tenantId != null) {
            TenantContext.bind(new TenantContext(tenantId));
        }
        Channel channel = channel(headers);
        String deviceId = text(headers, ServiceHeaders.DEVICE_ID);
        String appVersion = text(headers, ServiceHeaders.APP_VERSION);
        if (channel != null || deviceId != null || appVersion != null) {
            ClientContext.bind(new ClientContext(channel, deviceId, appVersion));
        }
        Locale locale = locale(headers);
        if (locale != null) {
            LocaleContext.bind(new LocaleContext(locale));
        }
        return scope;
    }

    private static String text(Map<String, ?> headers, String name) {
        Object value = headers.get(name);
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    private static Channel channel(Map<String, ?> headers) {
        String raw = text(headers, ServiceHeaders.CHANNEL);
        if (raw == null) {
            return null;
        }
        try {
            return Channel.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 语言头恢复（11.6.3）：值为约定 language tag，仅做值解析不做协商（BR-011 的解析入口归
     * framework-i18n，本方法只还原发送侧透传值）；非法值返回 {@code null} 不写入。
     */
    private static Locale locale(Map<String, ?> headers) {
        String raw = text(headers, ServiceHeaders.LOCALE);
        if (raw == null) {
            return null;
        }
        try {
            Locale parsed = Locale.forLanguageTag(raw.replace('_', '-'));
            return parsed.getLanguage().isEmpty() ? null : parsed;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
