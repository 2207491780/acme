package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository;
import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.common.core.BizException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.flex.support.FlexError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-20261128-092210 P1-04 备案下发竞态收敛单测（AC-16）：TASK_COMPLETED 事件消费者与备案
 * handler 并发同乐观锁行——败者重试/跳过收敛，状态机单向推进无回退。
 *
 * <p>竞态裁决机制=AuditBase {@code @Column(version=true)} 乐观锁（0 行更新=仓储层转
 * {@link FlexError#STALE_WRITE}）：
 * <ul>
 *   <li>handler 侧败者：两步推进落库撞 STALE_WRITE → 异常上抛不吞（SDK 接收端点 5xx 反噬 →
 *       引擎既有重试 3×1s → 重投见 APPROVING 单步落库 FILED）——AC-15 承载同一收敛面；</li>
 *   <li>消费者侧败者：见非 APPLYING 即跳过（APPROVING 重复落库/FILED 回退均不发生）。</li>
 * </ul>
 */
class REQ20261128092210LeaveFileDispatchRaceTest {

    private LeaveRequestRepository repository;
    private LeaveStartTaskRepository startTaskRepository;
    private LeaveRequestUseCase useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LeaveRequestRepository.class);
        startTaskRepository = mock(LeaveStartTaskRepository.class);
        ObjectProvider<LeaveProcessLauncher> launcher = mock(ObjectProvider.class);
        when(launcher.getObject()).thenReturn(() -> 7L);
        useCase = new LeaveRequestUseCase(repository,
                mock(com.acme.platform.workflow.api.WorkflowRuntimeClient.class), launcher,
                new IdGeneratorStub(), mock(LeaveAuthorizationGateway.class), startTaskRepository);
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

    // ---------- AC-16：handler 侧败者（STALE_WRITE 上抛 → 引擎重投收敛） ----------

    @Test
    void dispatchHandlerStaleWriteLosesRaceThenRedeliveryConvergesForwardOnly() {
        // 第一轮：handler 首读 APPLYING；第一步落库瞬间消费者已推进同乐观锁行（版本已被并发修改）
        // → STALE_WRITE 上抛（不吞——交 SDK 5xx 反噬引擎重试），FILED 不被写入
        LeaveRequest stale = savedLeave(42L, LeaveStatus.APPLYING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(stale));
        List<LeaveStatus> attemptedStatuses = new ArrayList<>();
        when(repository.save(any(LeaveRequest.class))).thenAnswer(invocation -> {
            attemptedStatuses.add(((LeaveRequest) invocation.getArgument(0)).getStatus());
            throw new BizException(FlexError.STALE_WRITE);
        });

        assertThatThrownBy(() -> useCase.fileByServiceTask(9001L, "leave-e2e-1"))
                .as("乐观锁败者异常原样上抛（引擎重试承载），不吞不降级")
                .isInstanceOf(BizException.class);
        assertThat(attemptedStatuses).as("FILED 不被写入（仅第一步 APPROVING 尝试落库）")
                .containsExactly(LeaveStatus.APPROVING);

        // 重投（引擎重试）：首读已见消费者推进的 APPROVING → 单步落库 FILED，无状态回退
        LeaveRequest latest = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(latest));
        when(repository.save(any(LeaveRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = useCase.fileByServiceTask(9001L, "leave-e2e-1");

        assertThat(result).containsEntry("filed", Boolean.TRUE);
        assertThat(latest.getStatus()).as("重投收敛：APPROVING 单步前向 FILED").isEqualTo(LeaveStatus.FILED);
        verify(repository, times(1)).save(latest);
    }

    // ---------- AC-16：消费者侧败者（见非 APPLYING 即跳过） ----------

    @Test
    void consumerSkipsWhenDispatchHandlerAlreadyFiled() {
        // handler 两步推进已至 FILED → 消费者见非 APPLYING 跳过（状态机单向推进无回退）
        LeaveRequest leave = savedLeave(42L, LeaveStatus.FILED);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        useCase.onTaskCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.FILED);
        verify(repository, never()).save(any());
    }

    @Test
    void consumerSkipsWhenDispatchHandlerAlreadyWroteApproving() {
        // handler 两步推进已过第一步（APPROVING）→ 消费者跳过（无重复落库、无 STALE_WRITE 噪声源）
        LeaveRequest leave = savedLeave(42L, LeaveStatus.APPROVING);
        when(repository.findByInstanceId(9001L)).thenReturn(Optional.of(leave));

        useCase.onTaskCompleted(9001L, "leave-e2e-1");

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.APPROVING);
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
