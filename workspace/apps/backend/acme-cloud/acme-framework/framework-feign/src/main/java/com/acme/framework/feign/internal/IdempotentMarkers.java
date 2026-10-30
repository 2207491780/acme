package com.acme.framework.feign.internal;

/**
 * 幂等标记（internal）：InvocationHandler 装饰层按 {@code @FeignIdempotent} 打标，
 * {@code ResilientFeignClient} 同步调用同线程读取（FR-019、取舍 #9）。
 */
public final class IdempotentMarkers {

    private static final ThreadLocal<Boolean> MARKER = new ThreadLocal<>();

    private IdempotentMarkers() {
    }

    public static void mark(boolean idempotent) {
        MARKER.set(idempotent);
    }

    public static Boolean current() {
        return MARKER.get();
    }

    public static void clear() {
        MARKER.remove();
    }
}
