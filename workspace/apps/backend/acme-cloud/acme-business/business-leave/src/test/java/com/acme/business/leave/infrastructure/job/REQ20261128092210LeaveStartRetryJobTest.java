package com.acme.business.leave.infrastructure.job;

import com.acme.business.leave.application.LeaveRequestUseCase;
import com.acme.business.leave.application.LeaveStartTaskScanGateway;
import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStartTask;
import com.acme.business.leave.domain.LeaveStartTaskStatus;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository;
import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.flex.support.FlexError;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-20261128-092210 P1-03 流程启动补偿重试任务单测（AC-4~AC-8 单元层支撑；集成面归 tester）：
 * 扫描捞取→乐观锁认领（恰一执行者）→绑定任务行租户上下文→启动+回写→失败按配置策略登记
 * （指数退避/EXHAUSTED），RUNNING 租约到期回收自愈。
 */
class REQ20261128092210LeaveStartRetryJobTest {

    private LeaveStartTaskScanGateway scanGateway;
    private LeaveStartTaskRepository taskRepository;
    private LeaveRequestRepository leaveRepository;
    private WorkflowRuntimeClient workflowRuntimeClient;
    private Environment environment;
    private LeaveStartRetryJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        scanGateway = mock(LeaveStartTaskScanGateway.class);
        taskRepository = mock(LeaveStartTaskRepository.class);
        leaveRepository = mock(LeaveRequestRepository.class);
        workflowRuntimeClient = mock(WorkflowRuntimeClient.class);
        ObjectProvider<LeaveProcessLauncher> launcher = mock(ObjectProvider.class);
        when(launcher.getObject()).thenReturn(() -> 7L);
        LeaveRequestUseCase useCase = new LeaveRequestUseCase(leaveRepository, workflowRuntimeClient,
                launcher, new IdGeneratorStub(), mock(LeaveAuthorizationGateway.class), taskRepository);
        // Environment 缺省桩：getProperty → null（job 取键面缺省值 10000/1000/5/120000/10000）
        environment = mock(Environment.class);
        job = new LeaveStartRetryJob(scanGateway, taskRepository, leaveRepository, useCase, environment);
        // 模拟生产态 @Scheduled 线程：无租户上下文（round-1 修复后——捞取经 @IgnoreTenant 门面
        // 跨租户，回收/认领/执行由 job 逐任务绑定行租户；本单测无上下文直驱即回归证明）
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static LeaveStartTask pendingTask(long id, long leaveId, int attempts) {
        LeaveStartTask task = new LeaveStartTask();
        task.setId(id);
        task.setTenantId("default");
        task.setLeaveId(leaveId);
        task.setStatus(LeaveStartTaskStatus.PENDING);
        task.setAttempts(attempts);
        task.setNextRetryAt(Instant.now().minusMillis(100));
        return task;
    }

    private static LeaveRequest retryLeave(long id) {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(id);
        leave.setTenantId("default");
        leave.setUserId("201");
        leave.setUserName("201");
        leave.setLeaveType("annual");
        leave.setStartTime(Instant.parse("2026-12-01T00:00:00Z"));
        leave.setEndTime(Instant.parse("2026-12-02T00:00:00Z"));
        leave.setStatus(LeaveStatus.APPLYING);
        return leave;
    }

    private void stubSuccessfulStart() {
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, 9001L));
    }

    // ---------- AC-4：到期任务扫描认领→启动成功→instance_id 回写+任务 DONE ----------

    @Test
    void scanClaimsDuePendingTaskAndWritesBackOnSuccess() {
        LeaveStartTask task = pendingTask(100L, 42L, 1);
        when(scanGateway.findDueTasks(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(List.of(task));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(LeaveStartTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LeaveRequest leave = retryLeave(42L);
        when(leaveRepository.findById(42L)).thenReturn(Optional.of(leave));
        AtomicReference<TenantContext> tenantAtStart = new AtomicReference<>();
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenAnswer(invocation -> {
                    tenantAtStart.set(TenantContext.get());
                    return new ContractResult<>(Boolean.TRUE, "0", null, 9001L);
                });

        job.scanAndRetry();

        // 补投请求形态：businessKey=请假单 id 字符串锚点、initiator/variables 自 leave 行
        verify(workflowRuntimeClient).startProcessInstance(argThat(request -> {
            assertThat(request.businessKey()).isEqualTo("42");
            assertThat(request.initiatorUserId()).isEqualTo("201");
            assertThat(request.definitionId()).isEqualTo(7L);
            assertThat(request.variables())
                    .containsEntry("leaveId", 42L)
                    .containsEntry("leaveType", "annual");
            return true;
        }));
        assertThat(leave.getInstanceId()).as("AC-4：instance_id 回写").isEqualTo(9001L);
        verify(leaveRepository).save(leave);
        assertThat(task.getStatus()).as("任务 DONE").isEqualTo(LeaveStartTaskStatus.DONE);
        assertThat(task.getAttempts()).as("成功不推进 attempts").isEqualTo(1);
        verify(taskRepository, times(2)).save(task);
        assertThat(tenantAtStart.get()).as("启动前按任务行租户显式回放上下文（事实 2）").isNotNull();
        assertThat(tenantAtStart.get().tenantId()).isEqualTo("default");
        assertThat(TenantContext.get()).as("finally 清理上下文").isNull();
    }

    // ---------- AC-4/AC-8：认领竞态乐观锁败者让位（恰一执行者） ----------

    @Test
    void scanSkipsTaskWhenClaimLosesOptimisticRace() {
        LeaveStartTask task = pendingTask(100L, 42L, 1);
        when(scanGateway.findDueTasks(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(List.of(task));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(LeaveStartTask.class)))
                .thenThrow(new BizException(FlexError.STALE_WRITE));

        job.scanAndRetry();

        verify(workflowRuntimeClient, never()).startProcessInstance(any());
        verify(leaveRepository, never()).save(any());
        assertThat(task.getStatus()).as("败者不启动不登记（让位给已认领执行者）")
                .isNotEqualTo(LeaveStartTaskStatus.EXHAUSTED);
    }

    // ---------- AC-7：RUNNING 超租约回收→同轮回 PENDING 重投（自愈） ----------

    @Test
    void scanReclaimsExpiredRunningLeaseThenReexecutes() {
        LeaveStartTask expired = pendingTask(100L, 42L, 2);
        expired.setStatus(LeaveStartTaskStatus.RUNNING);
        LeaveStartTask reclaimable = pendingTask(100L, 42L, 2);
        when(scanGateway.findDueTasks(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(List.of(expired));
        // 第一次读=租约回收装载（RUNNING），第二次读=认领装载（回收后 PENDING 形态）
        when(taskRepository.findById(100L))
                .thenReturn(Optional.of(expired), Optional.of(reclaimable));
        when(taskRepository.save(any(LeaveStartTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LeaveRequest leave = retryLeave(42L);
        when(leaveRepository.findById(42L)).thenReturn(Optional.of(leave));
        stubSuccessfulStart();

        job.scanAndRetry();

        assertThat(expired.getStatus()).as("租约到期回置 PENDING（AC-7 回收）")
                .isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(expired.getNextRetryAt()).as("回收即扫（next_retry_at=now）").isNotNull();
        verify(taskRepository, times(3)).save(any(LeaveStartTask.class));
        assertThat(reclaimable.getStatus()).as("同轮回 PENDING 重投→启动成功 DONE")
                .isEqualTo(LeaveStartTaskStatus.DONE);
        verify(workflowRuntimeClient).startProcessInstance(any(InternalProcessInstanceStartRequest.class));
        assertThat(leave.getInstanceId()).isEqualTo(9001L);
    }

    // ---------- AC-3/AC-4：启动失败登记补偿（指数退避，配置键面生效） ----------

    @Test
    void scanRegistersExponentialBackoffWithConfiguredBase() {
        // 配置键面生效（acme.leave.start-retry-backoff-base-ms=2000）
        when(environment.getProperty(LeaveStartRetryJob.BACKOFF_BASE_MS_KEY)).thenReturn("2000");
        LeaveStartTask task = pendingTask(100L, 42L, 1);
        when(scanGateway.findDueTasks(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(List.of(task));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(LeaveStartTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(leaveRepository.findById(42L)).thenReturn(Optional.of(retryLeave(42L)));
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        Instant before = Instant.now();
        job.scanAndRetry();

        verify(taskRepository, times(2)).save(any(LeaveStartTask.class));
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getNextRetryAt())
                .as("配置退避基值 2000ms：attempts=2 → +2×2000ms（键面生效）")
                .isBetween(before.plusMillis(3_800), before.plusMillis(4_200));
        assertThat(task.getLastError()).contains("RuntimeException").contains("boom");
        verify(leaveRepository, never()).save(any(LeaveRequest.class));
    }

    // ---------- AC-5：重试达配置上限 EXHAUSTED，leave 行保持 APPLYING ----------

    @Test
    void scanMarksExhaustedAtConfiguredMaxAttempts() {
        // 配置键面生效（acme.leave.start-retry-max-attempts=3）
        when(environment.getProperty(LeaveStartRetryJob.MAX_ATTEMPTS_KEY)).thenReturn("3");
        LeaveStartTask task = pendingTask(100L, 42L, 2);
        when(scanGateway.findDueTasks(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(List.of(task));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(LeaveStartTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(leaveRepository.findById(42L)).thenReturn(Optional.of(retryLeave(42L)));
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenThrow(new RuntimeException("persistent outage"));

        job.scanAndRetry();

        assertThat(task.getStatus()).as("配置上限=3：attempts 2→3 即 EXHAUSTED")
                .isEqualTo(LeaveStartTaskStatus.EXHAUSTED);
        assertThat(task.getAttempts()).isEqualTo(3);
        assertThat(task.getNextRetryAt()).isNull();
        assertThat(task.getLastError()).isNotBlank();
        verify(leaveRepository, never()).save(any(LeaveRequest.class));
    }

    // ---------- AC-4：单任务失败不外溢，其余任务继续补投 ----------

    @Test
    void scanContinuesRemainingTasksWhenOneTaskFails() {
        LeaveStartTask failing = pendingTask(100L, 42L, 1);
        LeaveStartTask succeeding = pendingTask(200L, 43L, 1);
        when(scanGateway.findDueTasks(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(List.of(failing, succeeding));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(failing));
        when(taskRepository.findById(200L)).thenReturn(Optional.of(succeeding));
        when(taskRepository.save(any(LeaveStartTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(leaveRepository.findById(42L)).thenReturn(Optional.of(retryLeave(42L)));
        when(leaveRepository.findById(43L)).thenReturn(Optional.of(retryLeave(43L)));
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenThrow(new RuntimeException("first boom"))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, 9002L));

        job.scanAndRetry();

        assertThat(failing.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(failing.getAttempts()).isEqualTo(2);
        assertThat(succeeding.getStatus()).as("失败任务不阻断后续补投")
                .isEqualTo(LeaveStartTaskStatus.DONE);
        verify(leaveRepository).save(argThat(saved -> {
            assertThat(saved.getInstanceId()).isEqualTo(9002L);
            return true;
        }));
    }

    // ---------- 补偿健壮性：请假单解析失败按失败登记（有界退避收敛，不外溢） ----------

    @Test
    void scanRegistersFailureWhenLeaveUnresolvable() {
        LeaveStartTask task = pendingTask(100L, 42L, 1);
        when(scanGateway.findDueTasks(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(List.of(task));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(LeaveStartTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(leaveRepository.findById(42L)).thenReturn(Optional.empty());

        job.scanAndRetry();

        verify(workflowRuntimeClient, never()).startProcessInstance(any());
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getLastError()).contains("IllegalStateException");
    }

    /** 递增 id 桩（41 起步，不与断言耦合）。 */
    private static final class IdGeneratorStub implements com.acme.common.util.IdGenerator {

        private long next = 41L;

        @Override
        public long nextId() {
            return ++next;
        }
    }
}
