package com.acme.framework.job.executor;

/**
 * 执行结果上报 wire 镜像（确认⑤；JOB-1 契约面 ExecutionResultReport 字段镜像）：
 * POST /internal/v1/scheduler/executions/{executionId}/result，state 允许 RUNNING/SUCCESS/FAILED/TIMEOUT
 * （TIMEOUT 归执行器侧超时判定上报，调度侧 CAS RUNNING→TIMEOUT 合法跃迁）。
 */
public record ResultReport(String state, String result, String error, Long costMs) {

    /** 状态：执行成功。 */
    public static final String STATE_SUCCESS = "SUCCESS";

    /** 状态：执行失败。 */
    public static final String STATE_FAILED = "FAILED";

    /** 状态：执行超时（执行器侧 timeoutMs 判定）。 */
    public static final String STATE_TIMEOUT = "TIMEOUT";
}
