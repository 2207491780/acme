package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.api.LoginLogStatsClient;
import com.acme.platform.auth.api.LoginLogStatsPoint;
import com.acme.platform.auth.api.OnlineCountClient;
import com.acme.platform.infra.api.ActionCount;
import com.acme.platform.infra.api.AuditStatsClient;
import com.acme.platform.system.api.ContractResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261119-011524 AC-1/AC-2/AC-3（system 聚合面）：GET /admin/v1/system/dashboard/summary
 * （platform-system，Feign 聚合＋本地 count＋unread＋60s 缓存）服务域集成测试
 * （Testcontainers PG+Redis，装配口径照抄 {@code REQ20261119082531ProfilePreferenceIntegrationTest}，
 * Feign 桩口径照抄 {@code REQ20261114120105OnlineUsersIntegrationTest} 的 MockitoBean 模式；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖（AC→用例映射，TL 已回填契约验收标准节）：</p>
 * <ul>
 *   <li>AC-1（消费面聚合）：{@link #should_aggregateLocalCountsAndFeignStats_when_allDownstreamsAvailable}
 *       ——三 internal 统计经 Feign 桩流入 summary 六字段＋days=14/30 钉值；三端点 producer 侧
 *       正确性由 auth/infra 自含 IT 覆盖（并行面，TL 汇总登记）。</li>
 *   <li>AC-2：{@link #should_degradeAllFeignFieldsToZeroOrEmpty_when_allDownstreamsFail}（全失败降级 0/空不 500）、
 *       {@link #should_keepHealthyFieldsAndDegradeOnlyFailedDownstream_when_singleDownstreamFails}（单点失败字段隔离）、
 *       {@link #should_returnSummaryToAnyAuthenticatedUser_when_noPermissionHeld}（认证即可/无新权限点钉面）。</li>
 *   <li>AC-3：{@link #should_hitCacheWithoutRepeatingFeignOutbound_when_secondCallWithinSixtySeconds}
 *       ——60s 内二次调用 Feign 桩 verify 次数不增（命中缓存不重复出站）。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 缓存拆分口径（实现落定回填）：静态部分（users/roles/tenants/online/trend/distribution）
 *       固定键全局 60s 缓存（ConcurrentHashMap+时间戳，确认记录⑥），unread 用户相关恒实时不入缓存
 *       （跨用户错读不可能——unread 每次直取本人）；静态缓存跨用例残留经 @DirtiesContext
 *       AFTER_EACH_TEST_METHOD 每方法重建上下文隔离（本地 count 断言不受先前用例种子污染）。</li>
 *   <li>AMB-2 本地计数口径假设：users/roles/tenants = 全平台非删除行数（sys_user/sys_role/sys_tenant
 *       deleted=false，跨租户、不按 status 过滤——对齐确认记录②「统计面是全平台概览」；用例内以
 *       租户 B 种子行钉跨租户口径）；unreadMessages = 调用者本人 sys_message is_read=false 且
 *       deleted=false 行数（他人未读不计入，对齐既有 unread-count 本人语义）。</li>
 *   <li>AMB-3 Feign 契约签名（api 契约落定后按实际签名机械回填）：
 *       {@code com.acme.platform.auth.api.OnlineCountClient#onlineCount() → ContractResult<Long>}
 *       （GET /internal/v1/online-count）；
 *       {@code com.acme.platform.auth.api.LoginLogStatsClient#dailyStats(int days) →
 *       ContractResult<List<LoginLogStatsPoint>>}（GET /internal/v1/login-log-stats；
 *       LoginLogStatsPoint(String date, long count)）；
 *       {@code com.acme.platform.infra.api.AuditStatsClient#actionDistribution(int days, int top) →
 *       ContractResult<List<ActionCount>>}（GET /internal/v1/audit-stats/distribution；
 *       ActionCount(String action, long count)——同名 record 各 api 模块独立定义）。
 *       DashboardUseCase 注入面 = api 模块 Feign 接口本体（单体适配器 LocalOnlineCountClient 等
 *       实现同接口；platform-system-service pom 增 platform-infra-api 依赖为本用例编译前置）。</li>
 *   <li>AMB-4 wire Long→string：DashboardSummaryView 五个 Long 标量字段（users/roles/tenants/
 *       onlineUsers/unreadMessages）断言 JSON 文本形态（isTextual）；嵌套 long count
 *       （trend/action 元素）wire 形态不钉（asText 数字/文本双兼容）。</li>
 *   <li>AMB-5 聚合透传假设：loginTrend/auditTopActions 原样透传下游列表（顺序保持、不补齐/重排）；
 *       days 参数值按契约钉 14（登录趋势）/30（审计分布），经精确桩参数＋verify 双重钉值。</li>
 *   <li>AMB-6 降级与身份：降级桩抛 RuntimeException（fail-open 预期宽 catch；若实现窄化为
 *       FeignException 再对齐桩类型）；调用者为内部令牌身份（无需 sys_user 行，unread 按
 *       sys_message.recipient_id 聚合）；「认证即可」用非超管角色声明（portal_user）＋零权限
 *       钉面——若实现误加权限点，本用例失败即契约违规信号。</li>
 *   <li>AMB-7 缓存时间窗：仅钉「60s 内二次调用命中」（verify 次数不增）；过期失效不在用例面
 *       （真实等待 60s 不可接受，契约未提供时间注入口）。</li>
 * </ul>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class REQ20261119011524DashboardSummaryIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SUMMARY_URL = "/admin/v1/system/dashboard/summary";

    /** 每用例独立调用者（AMB-1 per-user 缓存键假设下互不共享缓存条目）。 */
    private static final long CALLER_AGGREGATE = 115240101L;
    private static final long CALLER_DEGRADE_ALL = 115240201L;
    private static final long CALLER_DEGRADE_PARTIAL = 115240301L;
    private static final long CALLER_CACHE = 115240401L;
    private static final long CALLER_NO_PERM = 115240501L;

    /** 概览种子行 id（req 前缀隔离，teardown 全量回收）。 */
    private static final long TENANT_B = 115241001L;
    private static final long USER_B_TENANT = 115241101L;
    private static final long USER_DEFAULT = 115241102L;
    private static final long ROLE_DEFAULT = 115241201L;
    private static final long ROLE_TENANT_B = 115241202L;

    /** 在线数桩值（aggregate/no-perm 用例共享钉面）。 */
    private static final long ONLINE_STUB = 7L;

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 在线数 Feign 桩（auth-api 契约，AMB-3）。 */
    @MockitoBean
    private OnlineCountClient onlineCountClient;

    /** 登录日志按日统计 Feign 桩（auth-api 契约，AMB-3）。 */
    @MockitoBean
    private LoginLogStatsClient loginLogStatsClient;

    /** 审计动作分布 Feign 桩（infra-api 契约，AMB-3）。 */
    @MockitoBean
    private AuditStatsClient auditStatsClient;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown（本类独立容器，库内基线种子随容器销毁；此处仅回收本类种子行）
        jdbcTemplate.update("""
                delete from sys_message where recipient_id in (?, ?, ?, ?, ?, ?)
                """, CALLER_AGGREGATE, CALLER_DEGRADE_ALL, CALLER_DEGRADE_PARTIAL,
                CALLER_CACHE, CALLER_NO_PERM, USER_DEFAULT);
        jdbcTemplate.update("delete from sys_user where id in (?, ?)", USER_B_TENANT, USER_DEFAULT);
        jdbcTemplate.update("delete from sys_role where id in (?, ?)", ROLE_DEFAULT, ROLE_TENANT_B);
        jdbcTemplate.update("delete from sys_tenant where id = ?", TENANT_B);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1（消费面）＋AC-2 正向：下游全可用 → 六字段正确聚合（本地 count＋Feign 透传） ----------

    @Test
    void should_aggregateLocalCountsAndFeignStats_when_allDownstreamsAvailable() {
        seedOverviewRows();
        seedMessage(115242001L, CALLER_AGGREGATE, false);
        seedMessage(115242002L, CALLER_AGGREGATE, false);
        seedMessage(115242003L, CALLER_AGGREGATE, false);
        seedMessage(115242004L, CALLER_AGGREGATE, true);
        // 他人未读不计入本人（AMB-2 unread 本人维度钉面）
        seedMessage(115242005L, USER_DEFAULT, false);

        when(onlineCountClient.onlineCount()).thenReturn(new ContractResult<>(true, "0", "ok", ONLINE_STUB));
        when(loginLogStatsClient.dailyStats(14)).thenReturn(new ContractResult<>(true, "0", "ok", stubTrend()));
        when(auditStatsClient.actionDistribution(30, 5)).thenReturn(new ContractResult<>(true, "0", "ok", stubAudit()));

        // 期望值在调用前按 DB 实况计算（含 Flyway 基线种子，不硬编码基线规模；AMB-2 过滤口径）
        long expectedUsers = countNonDeleted("sys_user");
        long expectedRoles = countNonDeleted("sys_role");
        long expectedTenants = countNonDeleted("sys_tenant");

        JsonNode body = call(CALLER_AGGREGATE, Set.of("super_admin"));

        assertThat(body.path("code").asText()).as("下游全可用应成功").isEqualTo("0");
        JsonNode data = body.path("data");
        assertWireLongScalar(data, "users", expectedUsers);
        assertWireLongScalar(data, "roles", expectedRoles);
        assertWireLongScalar(data, "tenants", expectedTenants);
        assertWireLongScalar(data, "onlineUsers", ONLINE_STUB);
        assertWireLongScalar(data, "unreadMessages", 3L);

        JsonNode trend = data.path("loginTrend");
        assertThat(trend.isArray()).as("loginTrend 应为数组").isTrue();
        assertThat(trend.size()).as("登录趋势应透传下游 14 点（AMB-5）").isEqualTo(14);
        assertThat(trend.path(0).path("date").asText()).isEqualTo("2026-11-07");
        assertThat(trend.path(0).path("count").asText()).as("嵌套 count wire 形态不钉（AMB-4）").isEqualTo("1");
        assertThat(trend.path(13).path("date").asText()).isEqualTo("2026-11-20");
        assertThat(trend.path(13).path("count").asText()).isEqualTo("14");

        JsonNode audit = data.path("auditTopActions");
        assertThat(audit.isArray()).as("auditTopActions 应为数组").isTrue();
        assertThat(audit.size()).as("审计分布应透传下游 top5（AMB-5）").isEqualTo(5);
        assertThat(audit.path(0).path("action").asText()).isEqualTo("login");
        assertThat(audit.path(0).path("count").asText()).isEqualTo("120");
        assertThat(audit.path(4).path("action").asText()).isEqualTo("view");
        assertThat(audit.path(4).path("count").asText()).isEqualTo("20");

        // days/top 参数按契约钉值（AMB-5）
        verify(onlineCountClient, times(1)).onlineCount();
        verify(loginLogStatsClient, times(1)).dailyStats(14);
        verify(auditStatsClient, times(1)).actionDistribution(30, 5);
    }

    // ---------- AC-2 降级：下游全失败 → 对应字段 0/空，不 500；本地字段始终准确 ----------

    @Test
    void should_degradeAllFeignFieldsToZeroOrEmpty_when_allDownstreamsFail() {
        seedMessage(115242011L, CALLER_DEGRADE_ALL, false);
        seedMessage(115242012L, CALLER_DEGRADE_ALL, false);

        when(onlineCountClient.onlineCount()).thenThrow(new RuntimeException("req115240-online-down"));
        when(loginLogStatsClient.dailyStats(anyInt())).thenThrow(new RuntimeException("req115240-loginlog-down"));
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenThrow(new RuntimeException("req115240-audit-down"));

        long expectedUsers = countNonDeleted("sys_user");
        long expectedRoles = countNonDeleted("sys_role");
        long expectedTenants = countNonDeleted("sys_tenant");

        JsonNode body = call(CALLER_DEGRADE_ALL, Set.of("super_admin"));

        assertThat(body.path("code").asText()).as("下游失败应 fail-open 而非 500（业务码=0）").isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.path("onlineUsers").asText()).as("在线数降级 0（AMB-6 宽 catch 口径）").isEqualTo("0");
        assertThat(data.path("loginTrend").isArray()).isTrue();
        assertThat(data.path("loginTrend").size()).as("登录趋势降级空").isZero();
        assertThat(data.path("auditTopActions").isArray()).isTrue();
        assertThat(data.path("auditTopActions").size()).as("审计分布降级空").isZero();
        // 本地字段始终准确（契约 §API 降级条款）
        assertThat(data.path("users").asText()).isEqualTo(Long.toString(expectedUsers));
        assertThat(data.path("roles").asText()).isEqualTo(Long.toString(expectedRoles));
        assertThat(data.path("tenants").asText()).isEqualTo(Long.toString(expectedTenants));
        assertThat(data.path("unreadMessages").asText()).isEqualTo("2");
    }

    // ---------- AC-2 降级隔离：单点失败只降对应字段，健康字段保留 ----------

    @Test
    void should_keepHealthyFieldsAndDegradeOnlyFailedDownstream_when_singleDownstreamFails() {
        seedMessage(115242021L, CALLER_DEGRADE_PARTIAL, false);

        when(onlineCountClient.onlineCount()).thenReturn(new ContractResult<>(true, "0", "ok", ONLINE_STUB));
        when(loginLogStatsClient.dailyStats(anyInt())).thenReturn(new ContractResult<>(true, "0", "ok", stubTrend()));
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenThrow(new RuntimeException("req115240-audit-down"));

        JsonNode body = call(CALLER_DEGRADE_PARTIAL, Set.of("super_admin"));

        assertThat(body.path("code").asText()).as("单点失败仍应成功返回").isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.path("onlineUsers").asText()).as("健康下游字段保留").isEqualTo("7");
        assertThat(data.path("loginTrend").size()).as("健康下游字段保留（趋势 14 点）").isEqualTo(14);
        assertThat(data.path("auditTopActions").size()).as("仅失败下游字段降级空").isZero();
        assertThat(data.path("unreadMessages").asText()).isEqualTo("1");
    }

    // ---------- AC-3 缓存：60s 内二次调用命中缓存（Feign 桩 verify 次数不增） ----------

    @Test
    void should_hitCacheWithoutRepeatingFeignOutbound_when_secondCallWithinSixtySeconds() {
        seedMessage(115242031L, CALLER_CACHE, false);

        when(onlineCountClient.onlineCount()).thenReturn(new ContractResult<>(true, "0", "ok", ONLINE_STUB));
        when(loginLogStatsClient.dailyStats(anyInt())).thenReturn(new ContractResult<>(true, "0", "ok", stubTrend()));
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenReturn(new ContractResult<>(true, "0", "ok", stubAudit()));

        JsonNode first = call(CALLER_CACHE, Set.of("super_admin"));
        assertThat(first.path("code").asText()).isEqualTo("0");

        // 首次调用后 Feign 恰好一次出站
        verify(onlineCountClient, times(1)).onlineCount();
        verify(loginLogStatsClient, times(1)).dailyStats(anyInt());
        verify(auditStatsClient, times(1)).actionDistribution(anyInt(), anyInt());

        JsonNode second = call(CALLER_CACHE, Set.of("super_admin"));
        assertThat(second.path("code").asText()).isEqualTo("0");

        // 二次调用后出站次数不增（命中 60s 缓存，AMB-7 仅钉命中侧）
        verify(onlineCountClient, times(1)).onlineCount();
        verify(loginLogStatsClient, times(1)).dailyStats(anyInt());
        verify(auditStatsClient, times(1)).actionDistribution(anyInt(), anyInt());

        assertThat(second.path("data")).as("缓存命中应返回同一载荷").isEqualTo(first.path("data"));
    }

    // ---------- AC-2 认证即可钉面：无任何权限点/非超管身份 → 仍可访问 ----------

    @Test
    void should_returnSummaryToAnyAuthenticatedUser_when_noPermissionHeld() {
        seedMessage(115242041L, CALLER_NO_PERM, false);

        when(onlineCountClient.onlineCount()).thenReturn(new ContractResult<>(true, "0", "ok", ONLINE_STUB));
        when(loginLogStatsClient.dailyStats(anyInt())).thenReturn(new ContractResult<>(true, "0", "ok", stubTrend()));
        when(auditStatsClient.actionDistribution(anyInt(), anyInt())).thenReturn(new ContractResult<>(true, "0", "ok", stubAudit()));

        // 调用者无 sys_user 行、角色声明 portal_user（非超管、无任何绑定权限点）
        JsonNode body = call(CALLER_NO_PERM, Set.of("portal_user"));

        assertThat(body.path("code").asText())
                .as("summary 认证即可（确认记录④无新权限点）；若拒绝即契约违规信号")
                .isEqualTo("0");
        JsonNode data = body.path("data");
        assertThat(data.has("users")).as("六字段＋两数组齐备").isTrue();
        assertThat(data.has("roles")).isTrue();
        assertThat(data.has("tenants")).isTrue();
        assertThat(data.has("onlineUsers")).isTrue();
        assertThat(data.has("unreadMessages")).isTrue();
        assertThat(data.has("loginTrend")).isTrue();
        assertThat(data.has("auditTopActions")).isTrue();
        assertThat(data.path("onlineUsers").asText()).isEqualTo("7");
        assertThat(data.path("unreadMessages").asText()).isEqualTo("1");
    }

    // ---------- 支撑 ----------

    /** 非删除行计数（AMB-2：全平台口径、不按 status 过滤）。 */
    private long countNonDeleted(String table) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where deleted = false", Long.class);
        assertThat(count).as("计数查询应返回值：%s", table).isNotNull();
        return count == null ? 0L : count;
    }

    /** 调用者本人未读行计数（is_read=false 且未删，AMB-2）。 */
    private void seedMessage(long messageId, long recipientId, boolean read) {
        jdbcTemplate.update("""
                insert into sys_message (id, tenant_id, sender_id, recipient_id, title, content, is_read, read_at,
                                         created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'default', 1, ?, 'req115240-概览消息', '概览统计种子正文', ?, null,
                        now(), 'req115240-it', now(), 'req115240-it', 0, false)
                """, messageId, recipientId, read);
    }

    /** 概览种子（AMB-2 跨租户钉面）：租户 B＋双租户用户×2＋双租户角色×2。 */
    private void seedOverviewRows() {
        jdbcTemplate.update("""
                insert into sys_tenant (id, tenant_code, tenant_name, status, remark,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'req115240-tenant-b', '概览统计租户B', 'ENABLED', null,
                        now(), 'req115240-it', now(), 'req115240-it', 0, false)
                """, TENANT_B);
        jdbcTemplate.update("""
                insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'req115240-tenant-b', 'req115240-user-b', '租户B用户', null, null, 'ENABLED', false,
                        now(), 'req115240-it', now(), 'req115240-it', 0, false)
                """, USER_B_TENANT);
        jdbcTemplate.update("""
                insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'default', 'req115240-user-a', '默认租户用户', null, null, 'ENABLED', false,
                        now(), 'req115240-it', now(), 'req115240-it', 0, false)
                """, USER_DEFAULT);
        jdbcTemplate.update("""
                insert into sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'default', 'req115240_role_a', '概览角色A', 'ENABLED', null, false,
                        now(), 'req115240-it', now(), 'req115240-it', 0, false)
                """, ROLE_DEFAULT);
        jdbcTemplate.update("""
                insert into sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'req115240-tenant-b', 'req115240_role_b', '概览角色B', 'ENABLED', null, false,
                        now(), 'req115240-it', now(), 'req115240-it', 0, false)
                """, ROLE_TENANT_B);
    }

    /** 登录趋势桩数据：14 个按日升序点（契约 loginTrend ×14，AMB-5 透传钉面）。 */
    private static List<LoginLogStatsPoint> stubTrend() {
        String[] dates = {"2026-11-07", "2026-11-08", "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12",
                "2026-11-13", "2026-11-14", "2026-11-15", "2026-11-16", "2026-11-17", "2026-11-18",
                "2026-11-19", "2026-11-20"};
        List<LoginLogStatsPoint> points = new ArrayList<>();
        for (int i = 0; i < dates.length; i++) {
            points.add(new LoginLogStatsPoint(dates[i], i + 1L));
        }
        return List.copyOf(points);
    }

    /** 审计分布桩数据：5 点按 count 降序（契约 top5，AMB-5 透传钉面）。 */
    private static List<ActionCount> stubAudit() {
        return List.of(
                new ActionCount("login", 120L),
                new ActionCount("export", 80L),
                new ActionCount("upload", 60L),
                new ActionCount("delete", 40L),
                new ActionCount("view", 20L));
    }

    /** summary 六 Long 标量字段 wire 断言：文本形态（Long→string，AMB-4）＋值一致。 */
    private static void assertWireLongScalar(JsonNode data, String field, long expected) {
        JsonNode node = data.path(field);
        assertThat(node.isTextual())
                .as("summary.%s 应为 Long→string wire 形态（AMB-4，契约 §API wire Long→string）", field)
                .isTrue();
        assertThat(node.asText()).as("summary.%s 值", field).isEqualTo(Long.toString(expected));
    }

    /** 内部令牌身份发起 GET summary（范本口径：X-Acme-Channel + X-Acme-Token）。 */
    private JsonNode call(long userId, Set<String> roles) {
        try {
            MvcResult result = mvc.perform(get(SUMMARY_URL)
                            .header("X-Acme-Channel", "B")
                            .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                    String.valueOf(userId), "default", Channel.B, roles))))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("summary 请求失败：" + SUMMARY_URL, ex);
        }
    }
}
