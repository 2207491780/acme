package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.common.contract.InvalidRequestException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.util.IdGenerator;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-20261127-235808（B2b）事件锚点与完成链三态单测——覆盖 AC-6/AC-9/AC-10：
 * <ul>
 *   <li>AC-6：事件（TASK_COMPLETED/TASK_REJECTED/INSTANCE_COMPLETED/ServiceTask 下发）以 instanceId
 *       为唯一锚点解析单据；数字形态 businessKey（如 "42"）不再触发 {@code findById(Long.parseLong)}
 *       兜底（instanceId 查无时 {@code verify(repository, never()).findById(anyLong())}）；
 *       instanceId 查无 → 幂等跳过（on*）/ 统一查无口径（fileByServiceTask）；</li>
 *   <li>AC-9：INSTANCE_COMPLETED 消费时实例变量查询抛异常（故障注入）→ 不写 REJECTED 终态、
 *       不抛（单据保持原状态），后续重投/正常查询可继续推进；</li>
 *   <li>AC-10：实例变量 filed=true / filed=false 正常信封 → 分别落定 DONE / REJECTED（回归锁定）。</li>
 * </ul>
 *
 * <p>信封构造照 {@code LeaveRequestUseCaseTest} 范本：
 * {@code new ContractResult<>(Boolean.TRUE, "0", null, new WorkflowInstanceDetailView(...))}。</p>
 */
class REQ20261127235808LeaveEventAnchorTriStateTest {

    private LeaveRequestRepository repository;
    private WorkflowRuntimeClient workflowRuntimeClient;
    private LeaveRequestUseCase useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LeaveRequestRepository.class);
        workflowRuntimeClient = mock(WorkflowRuntimeClient.class);
        ObjectProvider<LeaveProcessLauncher> launcher = mock(ObjectProvider.class);
        when(launcher.getObject()).thenReturn(() -> 7L);
        useCase = new LeaveRequestUseCase(repository, workflowRuntimeClient, launcher,
                new IdGeneratorStub(), mock(com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway.class));
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
        leave.setStartTime(Instant.parse("2026-12-01T00:00:00Z"));
        leave.setEndTime(Instant.parse("2026-12-02T00:00:00Z"));
        leave.setStatus(status);
        return leave;
    }

    /** 正常信封（filed 变量由入参给定的 INSTANCE_COMPLETED 详情）。 */
    private void stubInstanceDetail(Map<String, Object> variables) {
        when(workflowRuntimeClient.processInstanceDetail(9001L)).thenReturn(new ContractResult<>(
                Boolean.TRUE, "0", null, new WorkflowInstanceDetailView(9001L, 7L, "leave-pilot",
                "leave-e2e-1", "201", WorkflowInstanceStatus.COMPLETED,
                Instant.now(), Instant.now(), variables, List.of())));
    }

    // ---------- AC-6：instanceId 唯一锚点（正路径） ----------

    @Test
    void taskCompletedResolvesByInstanceIdAnchorWithNumericBusinessKey() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPLYING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        useCase.onTaskCompleted(9001L, "42");

        assertThat(leave.getStatus()).as("instanceId 可解析 → 正常推进 APPROVING").isEqualTo(LeaveStatus.APPROVING);
        verify(repository).save(leave);
        // 数字形态 businessKey 不触发 findById 兜底（AC-6）
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void instanceCompletedResolvesByInstanceIdAnchorWithNumericBusinessKey() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.FILED);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        stubInstanceDetail(Map.of("filed", Boolean.TRUE));

        useCase.onInstanceCompleted(9001L, "42");

        assertThat(leave.getStatus()).as("instanceId 可解析 → 备案链走通落 DONE").isEqualTo(LeaveStatus.DONE);
        verify(repository).save(leave);
        verify(repository, never()).findById(anyLong());
    }

    // ---------- AC-6：instanceId 查无 + 数字形态 businessKey → 禁写单、禁兜底 ----------

    @Test
    void taskCompletedSkipsUnknownInstanceWithoutNumericBusinessKeyFallback() {
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.empty());

        assertThatCode(() -> useCase.onTaskCompleted(9001L, "42")).doesNotThrowAnyException();

        verify(repository, never()).findById(anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    void taskRejectedSkipsUnknownInstanceWithoutNumericBusinessKeyFallback() {
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.empty());

        assertThatCode(() -> useCase.onTaskRejected(9001L, "42")).doesNotThrowAnyException();

        verify(repository, never()).findById(anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    void instanceCompletedSkipsUnknownInstanceWithoutNumericBusinessKeyFallback() {
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.empty());

        assertThatCode(() -> useCase.onInstanceCompleted(9001L, "42")).doesNotThrowAnyException();

        verify(repository, never()).findById(anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    void fileByServiceTaskRejectsUnknownInstanceWithoutNumericBusinessKeyFallback() {
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.empty());

        InvalidRequestException ex = catchThrowableOfType(
                () -> useCase.fileByServiceTask(9001L, "42"), InvalidRequestException.class);
        assertThat(ex).as("ServiceTask 下发 instanceId 查无 → 统一查无口径（AC-6）").isNotNull();
        assertThat(ex.getMessage()).contains("请假单不存在");
        verify(repository, never()).findById(anyLong());
        verify(repository, never()).save(any());
    }

    // ---------- AC-9：实例变量查询 UNKNOWN → 不写 REJECTED 终态、可重投推进 ----------
    // 故障场景按契约口径取「未走备案链」单据（APPROVING，背景 §4 错杀 REJECTED 场景）；
    // 备案链已走通（FILED）的单据落点由 AC-10 正常信封路径回归锁定。

    @Test
    void instanceCompletedKeepsNonTerminalStateWhenInstanceDetailQueryFails() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        when(workflowRuntimeClient.processInstanceDetail(9001L))
                .thenThrow(new IllegalStateException("simulated outage"));

        assertThatCode(() -> useCase.onInstanceCompleted(9001L, "leave-e2e-1"))
                .as("UNKNOWN 处置按幂等跳过口径返回，不抛").doesNotThrowAnyException();

        assertThat(leave.getStatus()).as("UNKNOWN 不写 REJECTED 终态，单据保持原状态（AC-9）")
                .isEqualTo(LeaveStatus.APPROVING);
        verify(repository, never()).save(any());
    }

    @Test
    void instanceCompletedConvergesWhenDetailQueryRecoversOnRedelivery() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        when(workflowRuntimeClient.processInstanceDetail(9001L))
                .thenThrow(new IllegalStateException("simulated outage"))
                .thenReturn(new ContractResult<>(Boolean.TRUE, "0", null,
                        new WorkflowInstanceDetailView(9001L, 7L, "leave-pilot", "leave-e2e-1", "201",
                                WorkflowInstanceStatus.COMPLETED, Instant.now(), Instant.now(),
                                Map.of("filed", Boolean.TRUE), List.of())));

        // 第一次（UNKNOWN）：不写终态、不抛
        assertThatCode(() -> useCase.onInstanceCompleted(9001L, "leave-e2e-1")).doesNotThrowAnyException();
        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.APPROVING);
        verify(repository, never()).save(any());

        // 重投（查询恢复）：继续推进
        useCase.onInstanceCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).as("后续重投/正常查询可继续推进（AC-9）").isEqualTo(LeaveStatus.DONE);
        verify(repository).save(leave);
    }

    // ---------- AC-10：正常信封 filed=true/false 回归锁定 ----------

    @Test
    void instanceCompletedWritesDoneWhenFiledVariableTrue() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.FILED);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        stubInstanceDetail(Map.of("filed", Boolean.TRUE));

        useCase.onInstanceCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).as("备案链走通（filed=true）→ DONE（AC-10）").isEqualTo(LeaveStatus.DONE);
        verify(repository).save(leave);
    }

    @Test
    void instanceCompletedWritesRejectedWhenFiledVariableFalse() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        stubInstanceDetail(Map.of("filed", Boolean.FALSE));

        useCase.onInstanceCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).as("备案链未走通（filed=false）→ REJECTED（AC-10）")
                .isEqualTo(LeaveStatus.REJECTED);
        verify(repository).save(leave);
    }

    @Test
    void instanceCompletedWritesRejectedWhenVariablesEmpty() {
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));
        stubInstanceDetail(Map.of());

        useCase.onInstanceCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).as("无 filed 变量 → REJECTED（AC-10 回归锁定）")
                .isEqualTo(LeaveStatus.REJECTED);
        verify(repository).save(leave);
    }

    /** 递增 id 桩（41 起步，不与断言耦合）。 */
    private static final class IdGeneratorStub implements IdGenerator {

        private long next = 41L;

        @Override
        public long nextId() {
            return ++next;
        }
    }
}
