package com.acme.common.contract;

/**
 * 服务调用所需头部定义（FR-033；Q-005 结论归 contract；Backend.md 7.1.1、11.6.3 透传头）。
 * 头名取值按 Q-011 结论：{@code X-Acme-*} 统一前缀。供 framework-feign/stream 等注入与解析时引用。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class ServiceHeaders {

    /** 身份头（网关注入，framework-security 校验后写入上下文）。 */
    public static final String USER_ID = "X-Acme-User-Id";

    /** 租户头。 */
    public static final String TENANT_ID = "X-Acme-Tenant-Id";

    /** 端别（B/C）。 */
    public static final String CHANNEL = "X-Acme-Channel";

    /** 设备。 */
    public static final String DEVICE_ID = "X-Acme-Device-Id";

    /** 客户端版本。 */
    public static final String APP_VERSION = "X-Acme-App-Version";

    /** 语言头（11.6.3 透传；值为 language tag，如 {@code zh-CN} / {@code en}，与 Locale 解析覆盖头同名）。 */
    public static final String LOCALE = "X-Acme-Language";

    /** 内部服务令牌（Backend.md 3.13、11.6.1）。 */
    public static final String SERVICE_TOKEN = "X-Acme-Token";

    /** 操作人头（BR-014；取值 UserContext.userId()，缺值不注入）。 */
    public static final String OPERATOR = "X-Acme-Operator";

    /** 消息标识头（DP-009 消费预解析，FR-013/014）。 */
    public static final String MESSAGE_ID = "X-Acme-Message-Id";

    /** 幂等键头（DP-009 消费预解析，FR-013/014）。 */
    public static final String IDEMPOTENT_KEY = "X-Acme-Idempotent-Key";

    private ServiceHeaders() {
    }
}
