package com.acme.framework.trace.support;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * traceId/spanId 生成与 TraceContext + MDC 同步操作门面（FR-001；仿 ContextPropagators 形态）。
 * 生成口径：ThreadLocalRandom 直出 hex（traceId 32 位、spanId 16 位，格式兼容 DQ-001 与日志 pattern）；
 * 头名与 MDC key 一律取 {@link TraceConstants}（BR-013）。
 */
public final class Traces {

    private static final Logger log = LoggerFactory.getLogger(Traces.class);

    private static final int TRACE_ID_BYTES = 16;

    private static final int SPAN_ID_BYTES = 8;

    private Traces() {
    }

    /** 生成全局唯一 traceId：32 位无连字符 hex（FR-001、DQ-001）。 */
    public static String newTraceId() {
        return randomHex(TRACE_ID_BYTES);
    }

    /** 生成 spanId：16 位随机 hex（FR-001、DQ-001）。 */
    public static String newSpanId() {
        return randomHex(SPAN_ID_BYTES);
    }

    /** 读当前绑定；未绑定返回 null（FR-001）。 */
    public static TraceContext current() {
        return TraceContext.get();
    }

    /**
     * 复用或生成（FR-002~004 透传语义）：两头各自独立复用/生成（DQ-001）；
     * 缺失或空白按缺失处理（FR-002 异常分支）。
     */
    public static TraceContext resolve(String rawTraceId, String rawSpanId) {
        String traceId = hasText(rawTraceId) ? rawTraceId.trim() : newTraceId();
        String spanId = hasText(rawSpanId) ? rawSpanId.trim() : newSpanId();
        return new TraceContext(traceId, spanId);
    }

    /** 绑定当前线程：TraceContext + MDC 同步写入（FR-001）；重复绑定以最后一次为准。 */
    public static void bind(TraceContext ctx) {
        if (ctx == null) {
            clear();
            return;
        }
        TraceContext.bind(ctx);
        MDC.put(TraceConstants.MDC_TRACE_ID, ctx.traceId());
        MDC.put(TraceConstants.MDC_SPAN_ID, ctx.spanId());
    }

    /** 清理当前线程绑定（BR-014）；清理失败吞掉不外抛（FR-002）。 */
    public static void clear() {
        try {
            TraceContext.clear();
            MDC.remove(TraceConstants.MDC_TRACE_ID);
            MDC.remove(TraceConstants.MDC_SPAN_ID);
        } catch (RuntimeException ex) {
            log.debug("trace 上下文清理失败已忽略（FR-002）", ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 随机字节直出小写 hex，免去 UUID 字符串分配（FR-001 性能口径）。 */
    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(byteCount * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
