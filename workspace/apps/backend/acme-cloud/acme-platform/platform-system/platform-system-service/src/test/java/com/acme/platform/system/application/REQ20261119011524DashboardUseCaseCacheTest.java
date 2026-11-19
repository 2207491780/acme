package com.acme.platform.system.application;

import com.acme.platform.auth.api.LoginLogStatsClient;
import com.acme.platform.auth.api.LoginLogStatsPoint;
import com.acme.platform.auth.api.OnlineCountClient;
import com.acme.platform.infra.api.AuditStatsClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.dto.ActionCount;
import com.acme.platform.system.api.dto.DashboardSummaryView;
import com.acme.platform.system.api.dto.LoginTrendPoint;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首页概览聚合用例单测（REQ-20261119-011524 AC-2/AC-3 用例逻辑面，纯 JUnit 无 Spring）：
 * <ul>
 *   <li>AC-3 缓存：60s 内二次调用命中缓存（Feign 与本地 count 均不重复出站）、
 *       unread 用户相关不入缓存恒实时；</li>
 *   <li>AC-2 降级：Feign 下游失败 → 对应字段 0/空（统计面 fail-open 不抛），
 *       本地字段（unread）恒准确。</li>
 * </ul>
 */
class REQ20261119011524DashboardUseCaseCacheTest {

    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
    private final MessageUseCase messageUseCase = mock(MessageUseCase.class);
    private final OnlineCountClient onlineCountClient = mock(OnlineCountClient.class);
    private final LoginLogStatsClient loginLogStatsClient = mock(LoginLogStatsClient.class);
    private final AuditStatsClient auditStatsClient = mock(AuditStatsClient.class);

    @BeforeEach
    void setUp() {
        when(userRepository.countAllPlatformWide()).thenReturn(11L);
        when(roleRepository.countAllPlatformWide()).thenReturn(7L);
        when(tenantRepository.countAll()).thenReturn(3L);
    }

    private DashboardUseCase useCase() {
        return new DashboardUseCase(userRepository, roleRepository, tenantRepository, messageUseCase,
                onlineCountClient, loginLogStatsClient, auditStatsClient);
    }

    private static ContractResult<Long> online(long value) {
        return new ContractResult<>(true, "0", null, value);
    }

    private static ContractResult<List<LoginLogStatsPoint>> trend() {
        return new ContractResult<>(true, "0", null,
                List.of(new LoginLogStatsPoint("2026-11-20", 5L)));
    }

    private static ContractResult<List<com.acme.platform.infra.api.ActionCount>> audit() {
        // infra 同名 record（com.acme.platform.infra.api.ActionCount）经全限定消歧：
        // 桩面构造 Feign 契约类型，断言面构造本域视图类型（同名 record 分别映射）
        return new ContractResult<>(true, "0", null,
                List.of(new com.acme.platform.infra.api.ActionCount("login", 42L)));
    }

    // ---------- AC-3：60s 内二次调用命中缓存（Feign 与本地 count 不重复出站；unread 恒实时） ----------

    @Test
    void shouldHitStaticCacheWithoutRepeatingOutbound_whenSecondCallWithinTtl() {
        when(onlineCountClient.onlineCount()).thenReturn(online(9L));
        when(loginLogStatsClient.dailyStats(anyInt())).thenReturn(trend());
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenReturn(audit());

        DashboardUseCase useCase = useCase();
        DashboardSummaryView first = useCase.summary(1L);
        DashboardSummaryView second = useCase.summary(1L);

        // 静态字段（缓存值）：两次调用一致
        assertThat(first.users()).isEqualTo(11L);
        assertThat(second.users()).isEqualTo(11L);
        assertThat(second.onlineUsers()).isEqualTo(9L);
        assertThat(second.loginTrend()).containsExactly(new LoginTrendPoint("2026-11-20", 5L));
        assertThat(second.auditTopActions()).containsExactly(new ActionCount("login", 42L));

        // Feign 与本地 count 均只出站一次（命中缓存）
        verify(onlineCountClient, times(1)).onlineCount();
        verify(loginLogStatsClient, times(1)).dailyStats(anyInt());
        verify(auditStatsClient, times(1)).actionDistribution(anyInt(), anyInt());
        verify(userRepository, times(1)).countAllPlatformWide();

        // unread 用户相关不入缓存：两次调用各自实时取（recipientId 精确透传，不因命中缓存跳过）
        verify(messageUseCase, times(2)).unreadCount(1L);
    }

    @Test
    void shouldFetchUnreadRealtimeForEachCaller_whenCachedStaticsShared() {
        when(onlineCountClient.onlineCount()).thenReturn(online(9L));
        when(loginLogStatsClient.dailyStats(anyInt())).thenReturn(trend());
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenReturn(audit());
        when(messageUseCase.unreadCount(1L)).thenReturn(1);
        when(messageUseCase.unreadCount(2L)).thenReturn(5);

        DashboardUseCase useCase = useCase();
        DashboardSummaryView forUser1 = useCase.summary(1L);
        DashboardSummaryView forUser2 = useCase.summary(2L);

        // 同一份静态缓存 + 各自实时 unread（确认记录⑥缓存拆分：用户相关字段恒准确）
        assertThat(forUser1.unreadMessages()).isEqualTo(1L);
        assertThat(forUser2.unreadMessages()).isEqualTo(5L);
        assertThat(forUser1.onlineUsers()).isEqualTo(forUser2.onlineUsers());
        verify(messageUseCase, times(1)).unreadCount(1L);
        verify(messageUseCase, times(1)).unreadCount(2L);
    }

    // ---------- AC-2：Feign 下游失败 → 对应字段 0/空（fail-open），本地字段恒准确 ----------

    @Test
    void shouldDegradeFeignFieldsToZeroOrEmpty_whenDownstreamsFail() {
        when(onlineCountClient.onlineCount()).thenThrow(new RuntimeException("online-down"));
        when(loginLogStatsClient.dailyStats(anyInt())).thenThrow(new RuntimeException("trend-down"));
        when(auditStatsClient.actionDistribution(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("audit-down"));
        when(messageUseCase.unreadCount(1L)).thenReturn(4);

        DashboardSummaryView view = useCase().summary(1L);

        assertThat(view.onlineUsers()).as("在线数降级 0").isZero();
        assertThat(view.loginTrend()).as("登录趋势降级空").isEmpty();
        assertThat(view.auditTopActions()).as("审计分布降级空").isEmpty();
        assertThat(view.users()).as("本地字段不受下游影响").isEqualTo(11L);
        assertThat(view.roles()).isEqualTo(7L);
        assertThat(view.tenants()).isEqualTo(3L);
        assertThat(view.unreadMessages()).as("unread 本地字段恒准确").isEqualTo(4L);
    }

    @Test
    void shouldDegradeOnlyFailedDownstream_whenSingleDownstreamFails() {
        when(onlineCountClient.onlineCount()).thenThrow(new RuntimeException("online-down"));
        when(loginLogStatsClient.dailyStats(anyInt())).thenReturn(trend());
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenReturn(audit());

        DashboardSummaryView view = useCase().summary(1L);

        assertThat(view.onlineUsers()).as("仅失败下游降级").isZero();
        assertThat(view.loginTrend()).as("健康下游字段保留").hasSize(1);
        assertThat(view.auditTopActions()).hasSize(1);
    }

    // ---------- 出站参数钉面：days/top 按契约（14 / 30×5） ----------

    @Test
    void shouldPassContractWindows_whenAggregating() {
        when(onlineCountClient.onlineCount()).thenReturn(online(9L));
        when(loginLogStatsClient.dailyStats(anyInt())).thenReturn(trend());
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenReturn(audit());

        useCase().summary(1L);

        verify(loginLogStatsClient, times(1)).dailyStats(14);
        verify(auditStatsClient, times(1)).actionDistribution(30, 5);
    }
}
