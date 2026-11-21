package com.acme.framework.job.executor;

/**
 * 下发接单响应镜像（确认⑤；JOB-1 契约面 DispatchAcceptView 字段镜像）：
 * accepted=true = 已接单（调度侧推进 DISPATCHED→RUNNING）；accepted=false = 拒绝接单
 * （缺 handler / 执行器降级未注册等，调度侧 DISPATCH_REJECTED 走重试链）。
 */
public record AcceptView(boolean accepted) {

    /** 接单响应（accepted=true）。 */
    public static AcceptView ok() {
        return new AcceptView(true);
    }

    /** 拒绝接单响应（accepted=false）。 */
    public static AcceptView rejected() {
        return new AcceptView(false);
    }
}
