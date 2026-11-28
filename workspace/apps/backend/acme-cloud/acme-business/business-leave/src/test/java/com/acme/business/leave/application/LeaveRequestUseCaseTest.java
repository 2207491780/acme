package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStartTask;
import com.acme.business.leave.domain.LeaveStartTaskStatus;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository;
import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.common.contract.InvalidRequestException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假用例单测（REQ-20261122-233425 确认⑤ + REQ-20261128-092210 P1-03/P1-04 适配）：申请事务化
 * （apply 同事务落库两行、afterCommit 启动注册、无同步内联回退 AC-9、启动失败补偿登记）、HR 备案
 * 下发权威推进（AC-11/AC-12/AC-13/AC-14/AC-15 单元层）、TASK_REJECTED→REJECTED、INSTANCE_COMPLETED
 * 终态映射（备案链走通=filed 变量/FILED → DONE，else REJECTED）与幂等。
 *
 * <p>afterCommit 模拟口径（委派单事实 11）：{@code TransactionSynchronizationManager
 * .initSynchronization()/clearSynchronization()}——注册路径用例手动触发 afterCommit 回调断言
 * 启动/回写/补偿登记；内联回退用例不初始化同步（降级立即执行路径）。</p>
 */
class LeaveRequestUseCaseTest {

    private LeaveRequestRepository repository;
    private LeaveStartTaskRepository startTaskRepository;
    private WorkflowRuntimeClient workflowRuntimeClient;
    private LeaveAuthorizationGateway leaveAuthorizationGateway;
    private LeaveRequestUseCase useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LeaveRequestRepository.class);
        startTaskRepository = mock(LeaveStartTaskRepository.class);
        workflowRuntimeClient = mock(WorkflowRuntimeClient.class);
        leaveAuthorizationGateway = mock(LeaveAuthorizationGateway.class);
        ObjectProvider<LeaveProcessLauncher> launcher = mock(ObjectProvider.class);
        when(launcher.getObject()).thenReturn(() -> 7L);
        // 容器级生成器接口——单测取递增桩（生产由 WorkflowConfiguration 雪花 bean 承担）
        useCase = new LeaveRequestUseCase(repository, workflowRuntimeClient, launcher,
                new IdGeneratorStub(), leaveAuthorizationGateway, startTaskRepository);
        UserContext.bind(new UserContext("201", Set.of()));
        TenantContext.bind(new TenantContext("default"));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        UserContext.clear();
        TenantContext.clear();
    }

    private static LeaveRequest savedLeave(Long id, LeaveStatus status) {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(id);
        leave.setTenantId("default");
        leave.setUserId("201");
        leave.setUserName("201");
        leave.setLeaveType("annual");
        leave.setStartTime(Instant.parse("2026-11-23T00:00:00Z"));
        leave.setEndTime(Instant.parse("2026-11-24T00:00:00Z"));
        leave.setStatus(status);
        return leave;
    }

    private static LeaveApplyCommand applyCommand() {
        return new LeaveApplyCommand("leave-e2e-1", "annual", "年假",
                Instant.parse("2026-11-23T00:00:00Z"), Instant.parse("2026-11-24T00:00:00Z"));
    }

    /** afterCommit 注册路径开启（事务同步活跃——apply 降级判定走注册分支）。 */
    private static void startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    /** 手动触发已注册同步的 afterCommit 回调（事务提交语义模拟）。 */
    private static void fireAfterCommit() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    // ---------- P1-03：apply 事务化（afterCommit 注册路径，AC-1/AC-2 单元层支撑） ----------

    @Test
    void applyRegistersAfterCommitStartsOnCommitAndWritesBack() {
        startTransactionSynchronization();
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, 9001L));
        List<LeaveStartTaskStatus> taskStatuses = new ArrayList<>();
        doAnswer(invocation -> {
            taskStatuses.add(((LeaveStartTask) invocation.getArgument(0)).getStatus());
            return invocation.getArgument(0);
        }).when(startTaskRepository).save(any(LeaveStartTask.class));

        LeaveRequestView view = useCase.apply(applyCommand());

        // 确认⑤状态链序 + 契约 P1-03：落库驻留 APPLYING；流程启动已移出事务（响应前不启动）；
        // view 恒携 instanceId=null（前端 core leave.ts 已声明可空）
        assertThat(view.status()).isEqualTo(LeaveStatus.APPLYING);
        assertThat(view.instanceId()).as("启动后置——apply 响应 view.instanceId 恒 null").isNull();
        verify(workflowRuntimeClient, never()).startProcessInstance(any());
        // 同事务原子落库两行（单元层落库调用序；事务原子性由集成层承载）
        verify(repository).save(argThat(saved -> {
            assertThat(saved.getStatus()).isEqualTo(LeaveStatus.APPLYING);
            return true;
        }));
        verify(startTaskRepository).save(argThat(task -> {
            assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
            assertThat(task.getAttempts()).isEqualTo(0);
            return true;
        }));

        fireAfterCommit();

        // AC-2：启动成功 → instance_id 回写 + 任务 DONE（任务行 PENDING→RUNNING→DONE 三次落库）
        verify(workflowRuntimeClient).startProcessInstance(argThat(request -> {
            assertThat(request.businessKey()).isEqualTo("leave-e2e-1");
            assertThat(request.initiatorUserId()).isEqualTo("201");
            assertThat(request.definitionId()).isEqualTo(7L);
            assertThat(request.variables())
                    .containsEntry("leaveType", "annual")
                    .containsKey("leaveId");
            return true;
        }));
        assertThat(view.instanceId()).as("view 先于启动构建——响应口径恒 null").isNull();
        verify(repository, times(2)).save(any(LeaveRequest.class));
        assertThat(taskStatuses).as("任务行认领/终态落库序")
                .containsExactly(LeaveStartTaskStatus.PENDING, LeaveStartTaskStatus.RUNNING,
                        LeaveStartTaskStatus.DONE);
    }

    @Test
    void applyWithoutUserContextFailsFast() {
        UserContext.clear();
        assertThatThrownBy(() -> useCase.apply(applyCommand()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("用户上下文");
        verify(repository, never()).save(any());
        verify(startTaskRepository, never()).save(any());
    }

    @Test
    void applyWithoutTenantContextFailsFast() {
        TenantContext.clear();
        assertThatThrownBy(() -> useCase.apply(applyCommand()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("租户上下文");
        verify(repository, never()).save(any());
        verify(startTaskRepository, never()).save(any());
    }

    // ---------- P1-03：无活跃事务同步内联回退（AC-9，BR-017 防丢失语义） ----------

    @Test
    void applyInlineFallbackStartsImmediatelyWithoutTransactionSynchronization() {
        // 直调/单测态（无事务同步）：启动立即内联执行，行为与事务路径收敛一致（DB 态同径收敛）
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, 9001L));

        LeaveRequestView view = useCase.apply(applyCommand());

        assertThat(view.status()).isEqualTo(LeaveStatus.APPLYING);
        assertThat(view.instanceId()).as("view 先于启动构建——响应口径恒 null").isNull();
        // 内联立即启动（不待 afterCommit 触发）
        verify(workflowRuntimeClient).startProcessInstance(argThat(request -> {
            assertThat(request.businessKey()).isEqualTo("leave-e2e-1");
            assertThat(request.initiatorUserId()).isEqualTo("201");
            assertThat(request.definitionId()).isEqualTo(7L);
            return true;
        }));
        verify(repository, times(2)).save(any(LeaveRequest.class));
        verify(startTaskRepository, times(3)).save(any(LeaveStartTask.class));
    }

    // ---------- P1-03：启动失败补偿登记（AC-3 单元层支撑——全捕获不反噬） ----------

    @Test
    void applyStartFailureRegistersCompensationWithoutRebound() {
        startTransactionSynchronization();
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenThrow(new RuntimeException("simulated connection refused"));
        // 任务行落库捕获（同一实体引用状态随认领/登记演进——以终态断言登记结果）
        AtomicReference<LeaveStartTask> registeredTask = new AtomicReference<>();
        doAnswer(invocation -> {
            registeredTask.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        }).when(startTaskRepository).save(any(LeaveStartTask.class));

        LeaveRequestView view = useCase.apply(applyCommand());
        Instant beforeFailure = Instant.now();

        // afterCommit 回调体内全捕获：fireAfterCommit 不外抛（响应恒 2xx）
        fireAfterCommit();

        assertThat(view.status()).as("单据保持 APPLYING 非死态").isEqualTo(LeaveStatus.APPLYING);
        verify(repository, times(1)).save(any(LeaveRequest.class));
        // 落库三次：插入(PENDING,0) + 认领(RUNNING) + 失败登记(PENDING,1)
        verify(startTaskRepository, times(3)).save(any(LeaveStartTask.class));
        // AC-3：任务行 PENDING、attempts≥1、next_retry_at 指数退避（缺省 1s）、last_error 留痕
        LeaveStartTask task = registeredTask.get();
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(1);
        assertThat(task.getNextRetryAt()).isBetween(beforeFailure, beforeFailure.plusSeconds(2));
        assertThat(task.getLastError()).contains("RuntimeException");
    }

    @Test
    void applyStartPayloadMissingTreatedAsStartFailure() {
        // 委派单事实 10：ContractResult.payload() 判空防御——成功信封空 data 同按启动失败转补偿
        startTransactionSynchronization();
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, null));
        AtomicReference<LeaveStartTask> registeredTask = new AtomicReference<>();
        doAnswer(invocation -> {
            registeredTask.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        }).when(startTaskRepository).save(any(LeaveStartTask.class));

        useCase.apply(applyCommand());

        fireAfterCommit();

        verify(repository, times(1)).save(any(LeaveRequest.class));
        verify(startTaskRepository, times(3)).save(any(LeaveStartTask.class));
        LeaveStartTask task = registeredTask.get();
        assertThat(task.getStatus()).isEqualTo(LeaveStartTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(1);
    }

    // ---------- P1-04：HR 备案下发权威推进（AC-11/AC-12/AC-13/AC-14） ----------

    @Test
    void fileByServiceTaskTransitionsApprovingToFiled() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");

        assertThat(result).containsEntry("filed", Boolean.TRUE);
        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.FILED);
        verify(repository).save(leave);
    }

    @Test
    void fileByServiceTaskIsIdempotentWhenAlreadyFiled() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.FILED);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");

        assertThat(result).containsEntry("filed", Boolean.TRUE);
        verify(repository, never()).save(any());
    }

    @Test
    void fileByServiceTaskAdvancesApplyingTwoStepsForwardOnDispatch() {
        // AC-12（替代原轮询/超时用例）：APPLYING 收下发=审批办结权威证据——两步前向
        // APPROVING→FILED（各一次乐观锁落库，无状态跳写、无 Thread.sleep/轮询等待）
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPLYING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        List<LeaveStatus> savedStatuses = new ArrayList<>();
        when(repository.save(any(LeaveRequest.class))).thenAnswer(invocation -> {
            savedStatuses.add(((LeaveRequest) invocation.getArgument(0)).getStatus());
            return invocation.getArgument(0);
        });

        long start = System.currentTimeMillis();
        Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");
        long elapsedMillis = System.currentTimeMillis() - start;

        assertThat(result).containsEntry("filed", Boolean.TRUE);
        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.FILED);
        assertThat(savedStatuses).as("两步前向：先 APPROVING 后 FILED（无状态跳写）")
                .containsExactly(LeaveStatus.APPROVING, LeaveStatus.FILED);
        verify(repository, times(2)).save(leave);
        assertThat(elapsedMillis).as("无轮询等待（P1-04 去 sleep/轮询）").isLessThan(1_000);
    }

    @Test
    void fileByServiceTaskRefusesTerminalStatesWithoutWrite() {
        for (LeaveStatus terminal : List.of(LeaveStatus.DONE, LeaveStatus.REJECTED)) {
            LeaveRequest leave = savedLeave(42L, terminal);
            when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

            Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");

            assertThat(result).as("终态 %s 应不写 filed=true", terminal)
                    .containsEntry("filed", Boolean.FALSE);
            assertThat(result).as("终态 %s 应携带 reason", terminal).containsKey("reason");
            assertThat(leave.getStatus()).as("终态不应被 handler 改写：%s", terminal).isEqualTo(terminal);
        }
        verify(repository, never()).save(any());
    }

    // ---------- P1-04：瞬时异常上抛（AC-15 单元层——不吞，交引擎重试承载） ----------

    @Test
    void fileByServiceTaskTransientFailurePropagatesForEngineRetry() {
        // handler 瞬时异常（DB 故障）原样上抛——SDK 接收端点 5xx 反噬→引擎既有重试（3×1s）承载；
        // FILED 不被写入，终态判定仍归事件面（save 唯一尝试即抛，无后续吞咽/降级路径）
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        when(repository.save(any(LeaveRequest.class)))
                .thenThrow(new IllegalStateException("simulated db outage"));

        assertThatThrownBy(() -> useCase.fileByServiceTask(9001L, "leave-e2e-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated db outage");
        verify(repository, times(1)).save(any(LeaveRequest.class));
    }

    // ---------- 事件回写（确认⑤状态链序回归锁定） ----------

    @Test
    void taskCompletedAdvancesApplyingToApproving() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPLYING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        useCase.onTaskCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.APPROVING);
        verify(repository).save(leave);
    }

    @Test
    void taskCompletedKeepsFiledForwardOnly() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.FILED);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        useCase.onTaskCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.FILED);
        verify(repository, never()).save(any());
    }

    @Test
    void taskRejectedWritesRejectedTerminalState() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        useCase.onTaskRejected(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.REJECTED);
        verify(repository).save(leave);
    }

    @Test
    void instanceCompletedWritesDoneWhenFiledVariablePresent() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.FILED);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        when(workflowRuntimeClient.processInstanceDetail(9001L)).thenReturn(new ContractResult<>(
                Boolean.TRUE, "0", null, new WorkflowInstanceDetailView(9001L, 7L, "leave-pilot",
                "leave-e2e-1", "201", com.acme.platform.workflow.api.WorkflowInstanceStatus.COMPLETED,
                Instant.now(), Instant.now(), Map.of("filed", Boolean.TRUE), java.util.List.of())));

        useCase.onInstanceCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.DONE);
        verify(repository).save(leave);
    }

    @Test
    void instanceCompletedWritesRejectedWhenFilingChainNotReached() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        when(workflowRuntimeClient.processInstanceDetail(9001L)).thenReturn(new ContractResult<>(
                Boolean.TRUE, "0", null, new WorkflowInstanceDetailView(9001L, 7L, "leave-pilot",
                "leave-e2e-1", "201", com.acme.platform.workflow.api.WorkflowInstanceStatus.COMPLETED,
                Instant.now(), Instant.now(), Map.of(), java.util.List.of())));

        useCase.onInstanceCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.REJECTED);
        verify(repository).save(leave);
    }

    @Test
    void instanceCompletedIsIdempotentOnTerminalStates() {
        LeaveRequest done = savedLeave(42L, LeaveStatus.DONE);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(done));

        useCase.onInstanceCompleted(9001L, "leave-e2e-1");

        assertThat(done.getStatus()).isEqualTo(LeaveStatus.DONE);
        verify(repository, never()).save(any());
    }

    @Test
    void instanceCompletedSkipsForeignInstances() {
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.empty());

        useCase.onInstanceCompleted(9001L, "external-key");

        verify(repository, never()).save(any());
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
