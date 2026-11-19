package com.acme.platform.system.application;

import com.acme.framework.core.support.ServiceIdentityScope;
import com.acme.platform.auth.api.LoginLogStatsClient;
import com.acme.platform.auth.api.OnlineCountClient;
import com.acme.platform.infra.api.AuditStatsClient;
import com.acme.platform.system.api.dto.ActionCount;
import com.acme.platform.system.api.dto.DashboardSummaryView;
import com.acme.platform.system.api.dto.LoginTrendPoint;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 首页平台概览聚合用例（REQ-20261119-011524 确认记录①③⑥，GET /admin/v1/system/dashboard/summary
 * 数据源；认证即可无权限点——统计为平台概览数字面，确认记录④）：
 * <ul>
 *   <li>本地字段：users/roles 跨租户平台计数（仓储 {@code countAllPlatformWide} 显式旁路声明）、
 *       tenants 平台目录计数、unread=当前用户未读站内信（实时取，不入缓存）；</li>
 *   <li>Feign 聚合：在线数/登录趋势（platform-auth OnlineCountClient/LoginLogStatsClient）、
 *       审计动作分布（platform-infra AuditStatsClient），服务身份绑定（Q-D08，
 *       TenantSessionRevocationGateway 出站口径同型）；下游失败降级 0/空 + WARN 不 500
 *       （统计面 fail-open，前端以 — 展示）；</li>
 *   <li>60s 本地缓存（ConcurrentHashMap + 时间戳，确认记录⑥）：仅静态部分（users/roles/tenants/
 *       online/trend/distribution）入缓存，unread 用户相关恒实时——避免每次首页全链 Feign 出站。
 *       compute 原子加载防并发缓存击穿。</li>
 * </ul>
 */
@Service
public class DashboardUseCase {

    private static final Logger log = LoggerFactory.getLogger(DashboardUseCase.class);

    /** 服务身份主体标识（Q-D08：内部令牌 userId 填服务名，同 TenantSessionRevocationGateway 口径）。 */
    static final String SERVICE_IDENTITY = "platform-system";

    /** 静态部分本地缓存 TTL（确认记录⑥：60s）。 */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** 缓存键（概览聚合单键；用户相关 unread 不入缓存）。 */
    private static final String CACHE_KEY = "summary";

    /** 登录趋势窗口（确认记录①：近 14 日）。 */
    static final int LOGIN_TREND_DAYS = 14;

    /** 审计分布窗口与截断（确认记录①：近 30 日 top 5）。 */
    static final int AUDIT_STATS_DAYS = 30;
    static final int AUDIT_STATS_TOP = 5;

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysTenantRepository tenantRepository;
    private final MessageUseCase messageUseCase;
    private final OnlineCountClient onlineCountClient;
    private final LoginLogStatsClient loginLogStatsClient;
    private final AuditStatsClient auditStatsClient;

    /** 静态部分缓存（键固定 summary；值含时间戳，TTL 过期重算）。 */
    private final ConcurrentHashMap<String, CachedStats> cache = new ConcurrentHashMap<>();

    public DashboardUseCase(SysUserRepository userRepository,
                            SysRoleRepository roleRepository,
                            SysTenantRepository tenantRepository,
                            MessageUseCase messageUseCase,
                            OnlineCountClient onlineCountClient,
                            LoginLogStatsClient loginLogStatsClient,
                            AuditStatsClient auditStatsClient) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.messageUseCase = messageUseCase;
        this.onlineCountClient = onlineCountClient;
        this.loginLogStatsClient = loginLogStatsClient;
        this.auditStatsClient = auditStatsClient;
    }

    /**
     * 平台概览聚合（确认记录①②③⑥）：静态部分经 60s 缓存（命中不出站），unread 实时取。
     *
     * @param recipientId 当前用户 id（未读站内信口径，接口层自 UserContext.userId 解析）
     * @return 概览聚合视图
     */
    public DashboardSummaryView summary(Long recipientId) {
        CachedStats stats = cachedStaticStats();
        long unread = messageUseCase.unreadCount(recipientId == null ? 0L : recipientId);
        return new DashboardSummaryView(stats.users(), stats.roles(), stats.tenants(),
                stats.onlineUsers(), unread, stats.loginTrend(), stats.auditTopActions());
    }

    /** 缓存读：TTL 内命中返回快照；过期/缺失经 compute 原子重算（防并发击穿重复出站）。 */
    private CachedStats cachedStaticStats() {
        return cache.compute(CACHE_KEY, (key, existing) -> {
            if (existing != null && Instant.now().isBefore(existing.cachedAt().plus(CACHE_TTL))) {
                return existing;
            }
            return loadStaticStats();
        });
    }

    /** 静态部分加载（本地计数 + Feign 聚合降级）。 */
    private CachedStats loadStaticStats() {
        long users = userRepository.countAllPlatformWide();
        long roles = roleRepository.countAllPlatformWide();
        long tenants = tenantRepository.countAll();
        Long onlineUsers = degrade("platform-auth online-count", 0L,
                () -> withServiceIdentity(onlineCountClient::onlineCount).payload());
        List<LoginTrendPoint> loginTrend = degrade("platform-auth login-log-stats", List.of(),
                () -> withServiceIdentity(() -> loginLogStatsClient.dailyStats(LOGIN_TREND_DAYS))
                        .payload().stream()
                        .map(point -> new LoginTrendPoint(point.date(), point.count()))
                        .toList());
        List<ActionCount> auditTopActions = degrade("platform-infra audit-stats", List.of(),
                () -> withServiceIdentity(() -> auditStatsClient.actionDistribution(
                                AUDIT_STATS_DAYS, AUDIT_STATS_TOP))
                        .payload().stream()
                        // 同名 record 分别映射（两 api 模块不互相依赖，infra 行 → 本域视图，确认记录①）
                        .map(point -> new ActionCount(point.action(), point.count()))
                        .toList());
        return new CachedStats(Instant.now(), users, roles, tenants, onlineUsers,
                loginTrend, auditTopActions);
    }

    /**
     * Feign 降级包装（确认记录③：下游失败 → 缺省值 + WARN，统计面 fail-open 不 500）。
     * 仅拦运行时异常（Feign 错误解码 BizException / 连接故障等非受检面）。
     */
    private <T> T degrade(String downstream, T fallback, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException ex) {
            log.warn("首页概览统计下游调用失败，降级缺省值：downstream={} fallback={}",
                    downstream, fallback, ex);
            return fallback;
        }
    }

    /**
     * 服务身份出站包装（Q-D08，TenantSessionRevocationGateway 口径同型）：绑定 platform-system
     * 主体调用，finally 恢复原上下文（调用方主体不被出站覆盖）。
     */
    private <T> T withServiceIdentity(Supplier<T> call) {
        return ServiceIdentityScope.runAs(SERVICE_IDENTITY, call);
    }

    /**
     * 静态部分快照（缓存值；unread 用户相关不入本结构）。
     */
    record CachedStats(Instant cachedAt, long users, long roles, long tenants, Long onlineUsers,
                       List<LoginTrendPoint> loginTrend, List<ActionCount> auditTopActions) {
    }
}
