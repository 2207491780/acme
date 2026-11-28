package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.common.context.TenantContext;
import com.acme.common.util.IdGenerator;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费事件乱序到达守卫回归锁（REQ-20261128-183425 P2-12 AC-5）：分区键保序是发布面治理，
 * 消费侧守卫独立成立——终态已写后迟到 TASK_COMPLETED 不推进（防 REVIEW 所记「终态回写压掉
 * 中间态」的反向面=迟到中间态压掉终态）；正常序（TASK_COMPLETED 先、INSTANCE_COMPLETED 后）
 * 收敛不受守卫误伤。评审证据 `contracts/REVIEW-20261127-161433.md` P2-12。
 */
class REQ20261128183425EventOrderingGuardTest {

    private LeaveRequestRepository repository;
    private WorkflowRuntimeClient workflowRuntimeClient;
    private LeaveRequestUseCase useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LeaveRequestRepository.class);
        workflowRuntimeClient = mock(WorkflowRuntimeClient.class);
        ObjectProvider<LeaveProcessLauncher> launcher = mock(ObjectProvider.class);
        Mockito.when(launcher.getObject()).thenReturn(() -> 7L);
        useCase = new LeaveRequestUseCase(repository, workflowRuntimeClient, launcher,
                new IdGenerator() {
                    private long next = 41L;

                    @Override
                    public long nextId() {
                        return ++next;
                    }
                }, mock(LeaveAuthorizationGateway.class), mock(LeaveStartTaskRepository.class));
        TenantContext.bind(new TenantContext("default"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static LeaveRequest leave(long id, LeaveStatus status) {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(id);
        leave.setTenantId("default");
        leave.setUserId("201");
        leave.setUserName("201");
        leave.setLeaveType("annual");
        leave.setStartTime(Instant.parse("2026-12-01T00:00:00Z"));
        leave.setEndTime(Instant.parse("2026-12-02T00:00:00Z"));
        leave.setStatus(status);
        return leave;
    }

    private void stubInstanceView(long instanceId, Map<String, Object> variables) {
        when(workflowRuntimeClient.processInstanceDetail(instanceId)).thenReturn(
                new ContractResult<>(Boolean.TRUE, "0", null,
                        new WorkflowInstanceDetailView(instanceId, 7L, "leave", String.valueOf(instanceId),
                                "201", WorkflowInstanceStatus.RUNNING, null, null, variables, null)));
    }

    @Test
    void should_keep_rejected_terminal_when_late_task_completed_arrives() {
        // 乱序面：INSTANCE_COMPLETED 先到已写 REJECTED（审批驳回终态），TASK_COMPLETED 迟到
        LeaveRequest rejected = leave(42L, LeaveStatus.REJECTED);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(rejected));

        useCase.onTaskCompleted(9001L, "42");

        assertThat(rejected.getStatus()).as("终态不被迟到中间态事件回退").isEqualTo(LeaveStatus.REJECTED);
        verify(repository, never()).save(any(LeaveRequest.class));
    }

    @Test
    void should_keep_done_terminal_when_late_task_completed_arrives() {
        LeaveRequest done = leave(43L, LeaveStatus.DONE);
        when(repository.findByInstanceId(9002L)).thenReturn(Optional.of(done));

        useCase.onTaskCompleted(9002L, "43");

        assertThat(done.getStatus()).as("DONE 终态不被迟到 TASK_COMPLETED 压回").isEqualTo(LeaveStatus.DONE);
        verify(repository, never()).save(any(LeaveRequest.class));
    }

    @Test
    void should_converge_to_filed_done_when_task_completed_arrives_before_instance_completed() {
        // 正常序回归锚：TASK_COMPLETED 先（APPLYING→APPROVING）、INSTANCE_COMPLETED 后（filed=true→DONE）
        LeaveRequest applying = leave(44L, LeaveStatus.APPLYING);
        when(repository.findByInstanceId(9003L)).thenReturn(Optional.of(applying));
        AtomicReference<LeaveStatus> afterTaskCompleted = new AtomicReference<>();

        useCase.onTaskCompleted(9003L, "44");
        afterTaskCompleted.set(applying.getStatus());

        stubInstanceView(9003L, Map.of("filed", Boolean.TRUE));
        useCase.onInstanceCompleted(9003L, "44");

        assertThat(afterTaskCompleted.get()).as("TASK_COMPLETED 先到推进 APPROVING").isEqualTo(LeaveStatus.APPROVING);
        assertThat(applying.getStatus()).as("INSTANCE_COMPLETED 后到收敛 DONE（守卫不误伤正常序）")
                .isEqualTo(LeaveStatus.DONE);
        verify(repository, times(2)).save(applying);
    }
}
