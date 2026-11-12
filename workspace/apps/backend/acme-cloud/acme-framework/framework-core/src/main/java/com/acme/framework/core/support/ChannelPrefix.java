package com.acme.framework.core.support;

import com.acme.common.context.Channel;

/**
 * 端别路由前缀约定（Backend.md 11.1.3：B {@code /admin/**}、C {@code /app/**}），
 * 供 framework-gateway 越端判定（FR-019）与 framework-security 端别声明校验（FR-015）同源引用。
 */
public final class ChannelPrefix {

    /** B 端前缀（Backend.md 11.1.3）。 */
    public static final String ADMIN = "/admin";

    /** C 端前缀（Backend.md 11.1.3）。 */
    public static final String APP = "/app";

    private ChannelPrefix() {
    }

    /** 端别对应前缀；channel 为 null 返回 null。 */
    public static String of(Channel channel) {
        if (channel == null) {
            return null;
        }
        return channel == Channel.B ? ADMIN : APP;
    }

    /** 路径是否归属该端别前缀。 */
    public static boolean matches(Channel channel, String path) {
        String prefix = of(channel);
        return prefix != null && path != null && path.startsWith(prefix);
    }
}
