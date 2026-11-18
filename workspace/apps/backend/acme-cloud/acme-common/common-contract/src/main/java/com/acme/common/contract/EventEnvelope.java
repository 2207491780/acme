package com.acme.common.contract;

/**
 * 事件信封（FR-021、DP-009 定稿）：协议字段集收敛为 {messageId, traceId, idempotentKey, version, payload}，
 * 对齐 Backend.md 13.2.2（traceId、幂等键、版本号）与 FR-013（messageId）；身份/租户/端别/操作人等
 * 上下文走 {@link ServiceHeaders} 约定头，不入信封（单一归属，避免双份漂移）。
 *
 * <p>四协议字段必填（空白即显式失败），{@code payload} 允许 null（纯事件通知）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class EventEnvelope<P> {

    private final String messageId;
    private final String traceId;
    private final String idempotentKey;
    private final String version;
    private final P payload;

    public EventEnvelope(String messageId, String traceId, String idempotentKey, String version, P payload) {
        // DP-009 定稿：四协议字段必填，payload 允许 null
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId 不能为空（DP-009）");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不能为空（DP-009）");
        }
        if (idempotentKey == null || idempotentKey.isBlank()) {
            throw new IllegalArgumentException("idempotentKey 不能为空（DP-009）");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空（DP-009）");
        }
        this.messageId = messageId;
        this.traceId = traceId;
        this.idempotentKey = idempotentKey;
        this.version = version;
        this.payload = payload;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getIdempotentKey() {
        return idempotentKey;
    }

    public String getVersion() {
        return version;
    }

    public P getPayload() {
        return payload;
    }
}
