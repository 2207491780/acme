package com.acme.business.leave.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 流程启动补偿任务（leave_start_task；REQ-20261128-092210 确认②）：apply 事务内与 leave_request
 * 同事务原子落库（PENDING, attempts=0）；一单一任务（uk_leave_start_task_leave）。
 * 状态语义域见 {@link LeaveStartTaskStatus}；重试参数键面 {@code acme.leave.start-retry-*}。
 */
@Table("leave_start_task")
public class LeaveStartTask extends AuditBase {

    /** last_error 列宽（varchar(1024)）——登记侧截断兜底。 */
    static final int LAST_ERROR_MAX_LENGTH = 1024;

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("leave_id")
    private Long leaveId;

    @Column("status")
    private LeaveStartTaskStatus status;

    @Column("attempts")
    private Integer attempts;

    @Column("next_retry_at")
    private Instant nextRetryAt;

    @Column("last_error")
    private String lastError;

    public String getTenantId() {
        return tenantId;
    }

    public Long getLeaveId() {
        return leaveId;
    }

    public LeaveStartTaskStatus getStatus() {
        return status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setLeaveId(Long leaveId) {
        this.leaveId = leaveId;
    }

    public void setStatus(LeaveStartTaskStatus status) {
        this.status = status;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * 启动失败登记（委派单事实 9 落点）：attempts+1、last_error 留痕（超长截断）、指数退避回
     * PENDING；达尝试上限 → EXHAUSTED（next_retry_at 清空）。退避序列=base×2^(attempts-1)
     * （缺省 1s→2s→4s→8s→16s）。纯状态迁移（无落库副作用），调用方负责 save。
     *
     * @param now           失败登记时点（退避基准）
     * @param backoffBaseMs 退避基值毫秒（缺省 1000；配置面 acme.leave.start-retry-backoff-base-ms）
     * @param maxAttempts   尝试上限（缺省 5；配置面 acme.leave.start-retry-max-attempts）
     * @param error         错误摘要（类名+消息，超长截断）
     */
    public void registerFailure(Instant now, long backoffBaseMs, int maxAttempts, String error) {
        int nextAttempts = (attempts == null ? 0 : attempts) + 1;
        this.attempts = nextAttempts;
        this.lastError = truncateLastError(error);
        if (nextAttempts >= maxAttempts) {
            this.status = LeaveStartTaskStatus.EXHAUSTED;
            this.nextRetryAt = null;
            return;
        }
        this.status = LeaveStartTaskStatus.PENDING;
        // 位移上限兜底（防极端 attempts 配置溢出：>16 次后退避平台化）
        long backoffMs = backoffBaseMs * (1L << Math.min(nextAttempts - 1, 16));
        this.nextRetryAt = now.plusMillis(backoffMs);
    }

    private static String truncateLastError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= LAST_ERROR_MAX_LENGTH ? error : error.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
