package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.common.contract.InvalidRequestException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.util.IdGenerator;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-20261127-235808（B2b P1-05）请假单详情越权收口单测——覆盖 AC-1/AC-2/AC-3/AC-4：
 * <ul>
 *   <li>AC-1：owner 直读本人单据，不经权限服务（{@code findByIdAndUserId} 命中 + 权限网关零调用）；</li>
 *   <li>AC-2：同租户非 owner 且不持授权码 → 与「单据不存在」同口径（InvalidRequestException，
 *       消息含「请假单不存在」，不泄露存在性）；</li>
 *   <li>AC-3：非 owner 持 {@code workflow:task:list} / {@code workflow:instance:manage} 任一
 *       权限码（案B，确认记录⑦）→ 放行返回视图；</li>
 *   <li>AC-4：授权判定 UNKNOWN（权限服务异常注入）→ fail-closed 查无（异常不穿透）；
 *       owner 路径不依赖权限服务仍可直读。</li>
 * </ul>
 *
 * <p>装配缝照委派单钉死事实：{@code LeaveRequestUseCase} 六参构造（REQ-20261128-092210 P1-03
 * 追加尾参）（repository / workflowRuntimeClient / ObjectProvider&lt;LeaveProcessLauncher&gt; /
 * IdGenerator / LeaveAuthorizationGateway / LeaveStartTaskRepository）；get() 行为序 = owner 先行
 * （findByIdAndUserId）→ 未命中经 findById → 授权码判定任一持有放行 → 全不命中统一查无口径。
 * 租户维由 framework-tenant 插件承载（单测层为 repository mock，IT 层回归锁定）。</p>
 */
class REQ20261127235808LeaveDetailAuthzTest {

    /** 当前登录用户（owner 维对比锚）。 */
    private static final String CURRENT_USER = "201";

    private LeaveRequestRepository repository;
    private WorkflowRuntimeClient workflowRuntimeClient;
    private LeaveAuthorizationGateway leaveAuthorizationGateway;
    private AuthorizationClient authorizationClient;
    private ObjectProvider<LeaveProcessLauncher> launcher;
    private LeaveRequestUseCase useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LeaveRequestRepository.class);
        workflowRuntimeClient = mock(WorkflowRuntimeClient.class);
        leaveAuthorizationGateway = mock(LeaveAuthorizationGateway.class);
        authorizationClient = mock(AuthorizationClient.class);
        launcher = mock(ObjectProvider.class);
        when(launcher.getObject()).thenReturn(() -> 7L);
        useCase = new LeaveRequestUseCase(repository, workflowRuntimeClient, launcher,
                new IdGeneratorStub(), leaveAuthorizationGateway,
                mock(com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository.class));
        UserContext.bind(new UserContext(CURRENT_USER, Set.of()));
        TenantContext.bind(new TenantContext("default"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        TenantContext.clear();
    }

    /** 当前用户本人单据（owner=201）。 */
    private static LeaveRequest ownedLeave(Long id, LeaveStatus status) {
        return leaveOf(id, status, CURRENT_USER);
    }

    /** 同租户他人单据（owner=202，非当前用户）。 */
    private static LeaveRequest foreignLeave(Long id, LeaveStatus status) {
        return leaveOf(id, status, "202");
    }

    private static LeaveRequest leaveOf(Long id, LeaveStatus status, String userId) {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(id);
        leave.setTenantId("default");
        leave.setUserId(userId);
        leave.setUserName(userId);
        leave.setLeaveType("annual");
        leave.setStartTime(Instant.parse("2026-12-01T00:00:00Z"));
        leave.setEndTime(Instant.parse("2026-12-02T00:00:00Z"));
        leave.setStatus(status);
        return leave;
    }

    // ---------- AC-1：owner 直读，不经权限服务 ----------

    @Test
    void ownerReadsOwnRequestDirectlyWithoutPermissionConsultation() {
        LeaveRequest leave = ownedLeave(42L, LeaveStatus.FILED);
        when(repository.findByIdAndUserId(42L, CURRENT_USER)).thenReturn(Optional.of(leave));

        LeaveRequestView view = useCase.get(42L);

        assertThat(view.status()).as("owner 直读返回本人单据视图").isEqualTo(LeaveStatus.FILED);
        verify(repository).findByIdAndUserId(42L, CURRENT_USER);
        // owner 判定先行：权限网关零调用（AC-1 钉死口径）
        verify(leaveAuthorizationGateway, never()).hasPermission(anyString());
    }

    // ---------- AC-4（后半）：owner 路径不依赖权限服务 ----------

    @Test
    void ownerReadStillSucceedsWhenAuthorizationDownstreamFails() {
        LeaveRequest leave = ownedLeave(42L, LeaveStatus.FILED);
        when(repository.findByIdAndUserId(42L, CURRENT_USER)).thenReturn(Optional.of(leave));
        when(leaveAuthorizationGateway.hasPermission(anyString()))
                .thenThrow(new RuntimeException("simulated authorization outage"));

        LeaveRequestView view = useCase.get(42L);

        assertThat(view.status()).as("权限服务故障时 owner 直读不受影响（AC-4）").isEqualTo(LeaveStatus.FILED);
        verify(repository).findByIdAndUserId(42L, CURRENT_USER);
        verify(leaveAuthorizationGateway, never()).hasPermission(anyString());
    }

    // ---------- AC-3：授权角色放行（案B 两码任一持有） ----------

    @Test
    void authorizedRoleReadsForeignRequestWhenTaskListPermissionHeld() {
        LeaveRequest leave = foreignLeave(42L, LeaveStatus.FILED);
        when(repository.findByIdAndUserId(42L, CURRENT_USER)).thenReturn(Optional.empty());
        when(repository.findById(42L)).thenReturn(Optional.of(leave));
        when(leaveAuthorizationGateway.hasPermission(SystemPermissions.WORKFLOW_TASK_LIST)).thenReturn(true);
        when(leaveAuthorizationGateway.hasPermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)).thenReturn(false);

        LeaveRequestView view = useCase.get(42L);

        assertThat(view.status())
                .as("非 owner 持 workflow:task:list 即放行（案B 任一持有，AC-3）").isEqualTo(LeaveStatus.FILED);
        verify(repository).findByIdAndUserId(42L, CURRENT_USER);
        verify(repository).findById(42L);
        verify(leaveAuthorizationGateway, atLeastOnce()).hasPermission(anyString());
    }

    @Test
    void authorizedRoleReadsForeignRequestWhenInstanceManagePermissionHeld() {
        LeaveRequest leave = foreignLeave(42L, LeaveStatus.FILED);
        when(repository.findByIdAndUserId(42L, CURRENT_USER)).thenReturn(Optional.empty());
        when(repository.findById(42L)).thenReturn(Optional.of(leave));
        when(leaveAuthorizationGateway.hasPermission(SystemPermissions.WORKFLOW_TASK_LIST)).thenReturn(false);
        when(leaveAuthorizationGateway.hasPermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)).thenReturn(true);

        LeaveRequestView view = useCase.get(42L);

        assertThat(view.status())
                .as("非 owner 持 workflow:instance:manage 即放行（案B 任一持有，AC-3）").isEqualTo(LeaveStatus.FILED);
        verify(repository).findByIdAndUserId(42L, CURRENT_USER);
        verify(repository).findById(42L);
        verify(leaveAuthorizationGateway, atLeastOnce()).hasPermission(anyString());
    }

    // ---------- AC-2：非 owner 不持授权码 → 与查无同口径，不泄露存在性 ----------

    @Test
    void unauthorizedNonOwnerDeniedSameAsMissingRecord() {
        when(repository.findByIdAndUserId(42L, CURRENT_USER)).thenReturn(Optional.empty());
        when(repository.findById(42L)).thenReturn(Optional.of(foreignLeave(42L, LeaveStatus.FILED)));
        when(leaveAuthorizationGateway.hasPermission(anyString())).thenReturn(false);

        InvalidRequestException denied = catchThrowableOfType(
                () -> useCase.get(42L), InvalidRequestException.class);
        assertThat(denied).as("越权读应拒绝（AC-2）").isNotNull();
        assertThat(denied.getMessage()).as("与查无同口径消息").contains("请假单不存在");

        // 查无基线：单据真不存在（findById 空）——两失败路径口径不可区分（不泄露存在性）
        when(repository.findById(42L)).thenReturn(Optional.empty());
        InvalidRequestException missing = catchThrowableOfType(
                () -> useCase.get(42L), InvalidRequestException.class);
        assertThat(missing).as("查无基线同口径").isNotNull();
        assertThat(missing.getMessage()).contains("请假单不存在");
        assertThat(normalizeDigits(denied.getMessage()))
                .as("越权命中与真查无响应不可区分（不泄露单据存在性）")
                .isEqualTo(normalizeDigits(missing.getMessage()));
    }

    @Test
    void missingUserContextTreatedAsNonOwnerAndDeniedAsMissing() {
        // 无用户上下文 → 按「非 owner」处置，最终查无（不抛「缺少用户上下文」口径）
        UserContext.clear();
        when(repository.findById(42L)).thenReturn(Optional.of(foreignLeave(42L, LeaveStatus.FILED)));
        when(leaveAuthorizationGateway.hasPermission(anyString())).thenReturn(false);

        InvalidRequestException denied = catchThrowableOfType(
                () -> useCase.get(42L), InvalidRequestException.class);
        assertThat(denied).as("无上下文读他人单据应查无").isNotNull();
        assertThat(denied.getMessage()).contains("请假单不存在");
        assertThat(denied.getMessage()).as("不得以「缺少用户上下文」口径拒读").doesNotContain("用户上下文");
    }

    // ---------- AC-4（前半）：授权判定 UNKNOWN → fail-closed 查无 ----------

    @Test
    void leaveAuthorizationGatewayFailsClosedWhenPermissionServiceFails() {
        // 委派单事实 1：leave 域网关对齐四域先例，异常→false fail-closed（直接钉死网关语义）
        LeaveAuthorizationGateway realGateway = new LeaveAuthorizationGateway(authorizationClient);
        when(authorizationClient.hasPermission(anyString()))
                .thenThrow(new RuntimeException("UNKNOWN 故障注入"));

        assertThat(realGateway.hasPermission(SystemPermissions.WORKFLOW_TASK_LIST))
                .as("权限服务异常 → false（fail-closed，异常不穿透）").isFalse();
    }

    @Test
    void authorizationUnknownFailsClosedToNotFoundSemantics() {
        // 故障注入在权限服务依赖面（AuthorizationClient），经真实 LeaveAuthorizationGateway
        // fail-closed 收口（事实 1：异常→false）→ UseCase 统一查无口径（AC-4：UNKNOWN 不放行）
        LeaveAuthorizationGateway realGateway = new LeaveAuthorizationGateway(authorizationClient);
        useCase = new LeaveRequestUseCase(repository, workflowRuntimeClient, launcher,
                new IdGeneratorStub(), realGateway,
                mock(com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository.class));
        when(repository.findByIdAndUserId(42L, CURRENT_USER)).thenReturn(Optional.empty());
        when(repository.findById(42L)).thenReturn(Optional.of(foreignLeave(42L, LeaveStatus.FILED)));
        when(authorizationClient.hasPermission(anyString()))
                .thenThrow(new RuntimeException("UNKNOWN 故障注入"));

        InvalidRequestException denied = catchThrowableOfType(
                () -> useCase.get(42L), InvalidRequestException.class);
        assertThat(denied).as("UNKNOWN 不放行：故障不穿透、统一查无口径（AC-4）").isNotNull();
        assertThat(denied.getMessage()).contains("请假单不存在");
    }

    /** 数字形态细节归一（单据 id 等差异抹平，仅比对响应口径形态）。 */
    private static String normalizeDigits(String message) {
        return message == null ? "" : message.replaceAll("\\d+", "#");
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
