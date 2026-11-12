package com.acme.framework.cache.support;

import com.acme.framework.core.support.KeyComposer;

/**
 * 缓存 key 规范承载（FR-008、BR-008）：{@code {服务}:c:{ns}:{biz…}}（Backend.md 12.3.2 服务前缀）；
 * 业务 key 语义由业务定义（Backend.md 8.2.1）。构造经 core {@link KeyComposer}（Modules.md 4.3）。
 */
public final class CacheKeys {

    private static final String SEGMENT = "c";

    private final KeyComposer keyComposer;

    public CacheKeys(KeyComposer keyComposer) {
        this.keyComposer = keyComposer;
    }

    /** 规范化缓存 key（namespace + 业务键段）。 */
    public String of(String namespace, String... parts) {
        String[] segments = new String[parts == null ? 2 : parts.length + 2];
        segments[0] = SEGMENT;
        segments[1] = namespace;
        if (parts != null) {
            System.arraycopy(parts, 0, segments, 2, parts.length);
        }
        return keyComposer.compose(segments);
    }

    /** 服务前缀（透传，便于日志/排查）。 */
    public String servicePrefix() {
        return keyComposer.servicePrefix();
    }
}
