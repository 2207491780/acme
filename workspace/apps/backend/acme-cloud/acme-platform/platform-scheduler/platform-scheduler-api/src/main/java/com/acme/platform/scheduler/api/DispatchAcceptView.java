package com.acme.platform.scheduler.api;

/**
 * 下发接单响应（设计规格 §4 响应语义终稿）：HTTP 200 且 {@code accepted=true} = 执行器已接单
 * （调度侧推进 DISPATCHED → RUNNING）；{@code accepted=false} = 拒绝接单（执行池满等，
 * 调度侧视为下发失败走重试）。缺省构造为已接单（执行器侧最少返回 {@code {"accepted":true}}）。
 *
 * @param accepted 是否接单
 */
public record DispatchAcceptView(boolean accepted) {

    /** 接单响应（accepted=true）。 */
    public static DispatchAcceptView ok() {
        return new DispatchAcceptView(true);
    }
}
