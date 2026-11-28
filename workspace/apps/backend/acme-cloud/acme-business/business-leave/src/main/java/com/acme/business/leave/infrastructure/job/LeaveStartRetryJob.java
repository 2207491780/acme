package com.acme.business.leave.infrastructure.job;

import com.acme.business.leave.application.LeaveRequestUseCase;
import com.acme.business.leave.application.LeaveStartTaskScanGateway;
import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStartTask;
import com.acme.business.leave.domain.LeaveStartTaskStatus;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository;
import com.acme.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 流程启动补偿重试任务（REQ-20261128-092210 确认②③；P1-03 补偿面）：扫描 leave_start_task
 * 到期任务认领补投——apply afterCommit 内联启动失败（PENDING+指数退避）/ JVM 提交后崩溃遗留
 * （PENDING attempts=0 超 grace）/ 执行器崩溃遗留（RUNNING 超租约）三腿捞取（仓储
 * findRetryable），认领经乐观锁 CAS（恰一执行者——多实例安全，P2-06 单体 ShedLock 失效已知，
 * 禁依赖 ShedLock/framework-job，不引入新依赖）。
 *
 * <p>租户口径（round-1 勘误，REQ-20261128-092210 集成实测裁决）：{@code FlexTenantFactory}
 * 缺租户上下文返回 UNBOUND_TENANT 哨兵——读恒空集、写被守卫拒，生产态 {@code @Scheduled}
 * 线程原「不绑上下文」口径为补偿死面。修正为：捞取读面经 {@link LeaveStartTaskScanGateway}
 * 的 {@code @IgnoreTenant} 旁路跨租户（切面经代理触发、访问点登记、审计单点）；回收/认领/
 * 执行写面**逐任务绑定行租户上下文**（TenantWriteGuard fail-closed 语义保持，防御纵深不放宽），
 * finally 清理。参数键面 {@code acme.leave.start-retry-{scan-ms|backoff-base-ms|max-attempts|
 * lease-ms|grace-ms}}，缺省 10000/1000（指数）/5/120000/10000。EXHAUSTED 仅结构化 warn 留痕
 * （告警通道与 DLQ 面归 P1-11 批，管理端人工重驱不进本批——确认④）。</p>
 */
@Component("leaveStartRetryJob")
public class LeaveStartRetryJob {

    private static final Logger log = LoggerFactory.getLogger(LeaveStartRetryJob.class);

    /** 扫描间隔毫秒键（@Scheduled fixedDelay 占位符同键单源）。 */
    static final String SCAN_MS_KEY = "acme.leave.start-retry-scan-ms";

    /** 退避基值毫秒键（缺省 1000，指数 1s→2s→4s→8s→16s）。 */
    static final String BACKOFF_BASE_MS_KEY = "acme.leave.start-retry-backoff-base-ms";

    /** 尝试上限键（缺省 5）。 */
    static final String MAX_ATTEMPTS_KEY = "acme.leave.start-retry-max-attempts";

    /** RUNNING 租约毫秒键（缺省 120000）。 */
    static final String LEASE_MS_KEY = "acme.leave.start-retry-lease-ms";

    /** 零尝试 grace 毫秒键（缺省 10000，防 afterCommit 崩溃丢任务）。 */
    static final String GRACE_MS_KEY = "acme.leave.start-retry-grace-ms";

    private final LeaveStartTaskScanGateway scanGateway;
    private final LeaveStartTaskRepository taskRepository;
    private final LeaveRequestRepository leaveRepository;
    private final LeaveRequestUseCase leaveRequestUseCase;
    private final Environment environment;

    public LeaveStartRetryJob(LeaveStartTaskScanGateway scanGateway,
                              LeaveStartTaskRepository taskRepository,
                              LeaveRequestRepository leaveRepository,
                              LeaveRequestUseCase leaveRequestUseCase,
                              Environment environment) {
        this.scanGateway = scanGateway;
        this.taskRepository = taskRepository;
        this.leaveRepository = leaveRepository;
        this.leaveRequestUseCase = leaveRequestUseCase;
        this.environment = environment;
    }

    /**
     * 扫描补投（public 无参可直调——集成测试与手动触发复用，audit retention execute 形态先例；
     * @Scheduled fixedDelay 周期兜底）。单轮：捞取（@IgnoreTenant 门面跨租户读）→ 逐任务绑定
     * 行租户上下文 → RUNNING 租约到期回收（乐观锁 CAS 回 PENDING）→ 认领（乐观锁 CAS，败者
     * 让位跳过）→ 启动+回写；失败按配置策略登记（attempts+1/指数退避/达上限 EXHAUSTED+结构化
     * warn）。循环内单任务异常不外溢（登记内部消化），其余任务继续补投；逐任务 finally 清理
     * 上下文（防线程残留跨租户泄漏）。
     */
    @Scheduled(fixedDelayString = "${" + SCAN_MS_KEY + ":10000}")
    public void scanAndRetry() {
        Instant now = Instant.now();
        long graceMs = longParam(GRACE_MS_KEY, 10_000L);
        long leaseMs = longParam(LEASE_MS_KEY, 120_000L);
        long backoffBaseMs = longParam(BACKOFF_BASE_MS_KEY, 1_000L);
        int maxAttempts = (int) longParam(MAX_ATTEMPTS_KEY, 5L);
        List<LeaveStartTask> due = scanGateway.findDueTasks(now, graceMs, leaseMs);
        for (LeaveStartTask candidate : due) {
            TenantContext.bind(new TenantContext(candidate.getTenantId()));
            try {
                LeaveStartTask reclaimable = reclaimIfLeaseExpired(candidate, now);
                if (reclaimable == null) {
                    continue;
                }
                LeaveStartTask claimed = claim(reclaimable.getId());
                if (claimed == null) {
                    continue;
                }
                execute(claimed, backoffBaseMs, maxAttempts);
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 执行已认领任务（上下文由调用方逐任务绑定）→ 解析请假单 → 启动+回写（UseCase 共享执行体）。 */
    private void execute(LeaveStartTask task, long backoffBaseMs, int maxAttempts) {
        try {
            LeaveRequest leave = leaveRepository.findById(task.getLeaveId()).orElse(null);
            if (leave == null) {
                throw new IllegalStateException("补偿任务无法解析请假单（已删或锚点漂移）：leaveId="
                        + task.getLeaveId());
            }
            // 重试补投 businessKey=请假单 id 字符串形态（LeaveRequestView 对账锚点口径；
            // definitionId 由试点 SPI 执行期解析，启动链失败转补偿）
            leaveRequestUseCase.startProcessAndWriteback(leave, task, String.valueOf(leave.getId()));
        } catch (RuntimeException ex) {
            registerFailure(task, backoffBaseMs, maxAttempts, ex);
        }
    }

    /**
     * RUNNING 租约到期回收（乐观锁 CAS 回置 PENDING 并即扫；败者=执行者尚在推进，让位跳过）。
     * 回收落库异常不外溢（留待下一轮扫描，updated_at 仍超租约即可再回收）。
     */
    private LeaveStartTask reclaimIfLeaseExpired(LeaveStartTask candidate, Instant now) {
        if (candidate.getStatus() != LeaveStartTaskStatus.RUNNING) {
            return candidate;
        }
        try {
            LeaveStartTask latest = taskRepository.findById(candidate.getId()).orElse(null);
            if (latest == null || latest.getStatus() != LeaveStartTaskStatus.RUNNING) {
                return null;
            }
            latest.setStatus(LeaveStartTaskStatus.PENDING);
            latest.setNextRetryAt(now);
            return taskRepository.save(latest);
        } catch (RuntimeException ex) {
            log.debug("租约回收竞态让位（他人已推进）：taskId={}", candidate.getId());
            return null;
        }
    }

    /**
     * 认领（乐观锁 CAS：load 最新行→PENDING→置 RUNNING→save；0 行更新=他人已认领——让位跳过，
     * 恰一执行者）。认领后任务行 RUNNING 超租约未终态 → 下一轮扫描回收自愈。
     */
    private LeaveStartTask claim(Long taskId) {
        try {
            LeaveStartTask latest = taskRepository.findById(taskId).orElse(null);
            if (latest == null || latest.getStatus() != LeaveStartTaskStatus.PENDING) {
                return null;
            }
            latest.setStatus(LeaveStartTaskStatus.RUNNING);
            return taskRepository.save(latest);
        } catch (RuntimeException ex) {
            log.debug("认领竞态让位（他人已认领）：taskId={}", taskId);
            return null;
        }
    }

    /**
     * 失败登记（委派单事实 9 落点）：attempts+1/next_retry_at 指数退避/last_error 留痕回 PENDING；
     * attempts 达上限 → EXHAUSTED+结构化 warn。登记落库自身异常不外溢（任务行留 RUNNING 留待
     * 租约回收自愈）；leave 行不触碰（保持 APPLYING 非死态，不写 APPROVING 及任何终态）。
     */
    private void registerFailure(LeaveStartTask task, long backoffBaseMs, int maxAttempts,
                                 RuntimeException ex) {
        try {
            task.registerFailure(Instant.now(), backoffBaseMs, maxAttempts,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            taskRepository.save(task);
        } catch (RuntimeException registrationEx) {
            log.warn("补偿任务失败登记落库异常（留待租约回收自愈）：taskId={}", task.getId(), registrationEx);
        }
        if (task.getStatus() == LeaveStartTaskStatus.EXHAUSTED) {
            log.warn("流程启动补偿重试耗尽（EXHAUSTED；告警面归 P1-11 批）：taskId={} leaveId={} "
                            + "attempts={} lastError={}", task.getId(), task.getLeaveId(),
                    task.getAttempts(), task.getLastError());
        } else {
            log.warn("补偿启动失败已登记重试：taskId={} leaveId={} status={} attempts={} nextRetryAt={}",
                    task.getId(), task.getLeaveId(), task.getStatus(), task.getAttempts(),
                    task.getNextRetryAt(), ex);
        }
    }

    /** 参数读取（键面缺省值；非法配置显式解析失败——fail-fast 不静默降级）。 */
    private long longParam(String key, long defaultValue) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }
}
