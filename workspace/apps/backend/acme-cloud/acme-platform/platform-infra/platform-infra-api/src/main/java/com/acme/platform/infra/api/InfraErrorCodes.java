package com.acme.platform.infra.api;

/**
 * 基础设施域错误码文本常量（Backend.md 10.5.2，serviceId=platform-infra）：
 * 完整枚举（含 HTTP 映射）归 platform-infra-service InfraError，两处取值一致由单测锁定。
 */
public final class InfraErrorCodes {

    /** 审计事件无效（400：协议字段缺失/非法，消费侧毒消息转死信）。 */
    public static final String AUDIT_EVENT_INVALID = "platform-infra-01-0001";

    /** 审计存储失败（500：落库异常，重试后转死信）。 */
    public static final String AUDIT_STORE_FAILED = "platform-infra-01-0002";

    private InfraErrorCodes() {
    }
}
