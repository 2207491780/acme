package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假用例单测（REQ-20261122-233425 确认⑤/AC-6/AC-7 语义面）：申请→启动绑 businessKey（状态驻留
 * APPLYING——APPROVING 随审批办结事件）、HR 备案 APPROVING→FILED 幂等、TASK_REJECTED→REJECTED、
 * INSTANCE_COMPLETED 终态映射（备案链走通=filed 变量/FILED → DONE，else REJECTED）与幂等。
 */
class LeaveRequestUseCaseTest {

    private LeaveRequestRepository repository;
    private WorkflowRuntimeClient workflowRuntimeClient;
    private LeaveAuthorizationGateway leaveAuthorizationGateway;
    private LeaveRequestUseCase useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LeaveRequestRepository.class);
        workflowRuntimeClient = mock(WorkflowRuntimeClient.class);
        leaveAuthorizationGateway = mock(LeaveAuthorizationGateway.class);
        ObjectProvider<LeaveProcessLauncher> launcher = mock(ObjectProvider.class);
        when(launcher.getObject()).thenReturn(() -> 7L);
        // 容器级生成器接口——单测取递增桩（生产由 WorkflowConfiguration 雪花 bean 承担）
        useCase = new LeaveRequestUseCase(repository, workflowRuntimeClient, launcher,
                new IdGeneratorStub(), leaveAuthorizationGateway);
        UserContext.bind(new UserContext("201", Set.of()));
        TenantContext.bind(new TenantContext("default"));
    }

    @AfterEach
    void tearDown() {
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

    @Test
    void applySavesApplyingAndBindsInstance() {
        when(workflowRuntimeClient.startProcessInstance(any(InternalProcessInstanceStartRequest.class)))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null, 9001L));

        LeaveRequestView view = useCase.apply(new LeaveApplyCommand("leave-e2e-1", "annual", "年假",
                Instant.parse("2026-11-23T00:00:00Z"), Instant.parse("2026-11-24T00:00:00Z")));

        // 确认⑤状态链序：APPROVING 落点=审批办结事件——申请落库后驻留 APPLYING
        assertThat(view.status()).isEqualTo(LeaveStatus.APPLYING);
        assertThat(view.instanceId()).isEqualTo(9001L);
        verify(workflowRuntimeClient).startProcessInstance(argThat(request -> {
            assertThat(request.businessKey()).isEqualTo("leave-e2e-1");
            assertThat(request.initiatorUserId()).isEqualTo("201");
            assertThat(request.definitionId()).isEqualTo(7L);
            return true;
        }));
    }

    @Test
    void applyWithoutUserContextFailsFast() {
        UserContext.clear();
        assertThatThrownBy(() -> useCase.apply(new LeaveApplyCommand("leave-e2e-1", "annual", null,
                Instant.now(), Instant.now())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("用户上下文");
        verify(repository, never()).save(any());
    }

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
    void fileByServiceTaskWaitsForApprovingThenFilesWhenConsumerLags() {
        // 消费滞后场景：handler 首读 APPLYING，等待期 TASK_COMPLETED 消费者推进 APPROVING（重读换行）
        LeaveRequest stale = savedLeave(42L, LeaveStatus.APPLYING);
        LeaveRequest latest = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(latest));

        Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");

        assertThat(result).containsEntry("filed", Boolean.TRUE);
        assertThat(latest.getStatus()).isEqualTo(LeaveStatus.FILED);
        verify(repository).save(latest);
    }

    @Test
    void fileByServiceTaskTimesOutStillWritesFiledForStaleApplying() {
        // 超时守卫（TL round-4）：等待 5s 仍 APPLYING（token 必经审批办结——陈旧读）照写 FILED
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPLYING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");

        assertThat(result).containsEntry("filed", Boolean.TRUE);
        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.FILED);
        verify(repository).save(leave);
    }

    @Test
    void fileByServiceTaskRefusesTerminalStatesWithoutWrite() {
        for (LeaveStatus terminal : List.of(LeaveStatus.DONE, LeaveStatus.REJECTED)) {
            LeaveRequest leave = savedLeave(42L, terminal);
            when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

            Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");

            assertThat(result).as("终态 %s 应不写 filed=true", terminal)
                    .containsEntry("filed", Boolean.FALSE);
            assertThat(leave.getStatus()).as("终态不应被 handler 改写：%s", terminal).isEqualTo(terminal);
        }
        verify(repository, never()).save(any());
    }

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
