package com.acme.common.context;

import com.acme.common.context.internal.ContextThreadLocals;

/**
 * 客户端上下文（FR-011）：channel（B/C 端标识）、deviceId、appVersion。只存值不做解析（BR-007）。
 *
 * <p>写入源唯一（BR-008）：网关注入后仅 framework-security 校验通过后写入（Modules.md 2.4.4、3.1.2）；
 * 业务代码只读——可见性无法强制该约束，靠规范（架构设计 11）。</p>
 *
 * <p>绑定口径（DP-006 定稿，与 {@link UserContext} 一致）：未绑定读取返回 {@code null}；
 * {@code bind(null)} 等价解绑；请求结束必须 {@link #clear()}（Modules.md 2.4.3）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class ClientContext {

    private final Channel channel;
    private final String deviceId;
    private final String appVersion;

    public ClientContext(Channel channel, String deviceId, String appVersion) {
        this.channel = channel;
        this.deviceId = deviceId;
        this.appVersion = appVersion;
    }

    public Channel channel() {
        return channel;
    }

    public String deviceId() {
        return deviceId;
    }

    public String appVersion() {
        return appVersion;
    }

    /** 绑定当前线程（FR-011）；{@code null} 即解绑；仅 framework-security 校验通过后写入（BR-008）。 */
    public static void bind(ClientContext value) {
        if (value == null) {
            ContextThreadLocals.CLIENT.remove();
        } else {
            ContextThreadLocals.CLIENT.set(value);
        }
    }

    /** 读取当前线程绑定值（FR-011）；未绑定返回 {@code null}（DP-006 定稿）；业务代码只读（BR-008）。 */
    public static ClientContext get() {
        return ContextThreadLocals.CLIENT.get();
    }

    /** 清理当前线程绑定（FR-011；Modules.md 2.4.3）。 */
    public static void clear() {
        ContextThreadLocals.CLIENT.remove();
    }
}
