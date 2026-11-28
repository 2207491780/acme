package com.acme.bootstrap;

import com.acme.business.leave.infrastructure.job.LeaveStartRetryJob;
import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.test.EmbeddedRedisSupport;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import com.acme.platform.workflow.api.dto.WorkflowVariablesWriteRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-092210（B4-事务 P1-03）apply 事务化 + afterCommit 启流程补偿重试集成测试
 * （bootstrap 单体聚合形态；AC-1~AC-8、AC-10 承载；AC-9/12/15/16 单测层归 backend-dev）。
 *
 * <p>装配口径照 {@code REQ20261122233425LeaveE2eIntegrationTest} / {@code MonolithContextSmokeTest}
 * 先例（MonolithApplication + RANDOM_PORT 真容器端口 + 零 Docker 嵌入式 PG/Redis + 种子密码/hmac
 * 环境键）；stream 保持测试域缺省关闭（补偿链不需要事件腿：TASK_COMPLETED 永不消费，单据推进
 * 全部由本批用例驱动的面承载）；独立隔离库 {@code acme_monolith_leave_comp}（后台 @Scheduled
 * 竞态防污染，flex-test-assembly 口径 6）。底座零 Docker 实跑，禁 SKIP 豁免。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 失败注入缝=内嵌 {@code @TestConfiguration}（经 {@code @Import} 注册）+ {@code @Primary}
 *     桩覆盖 {@code WorkflowRuntimeClient}（委派单钉面，Spring 上下文面注入；禁 new UseCase/
 *     禁 mock 仓储）。实测注记：单体 local 适配器自带 @Primary 且存在 Feign 工厂候选（bean 名=
 *     接口 FQCN），桩标 @Primary 会多 primary 歧义——经 BFPP 按产物类型降级其余 client 定义
 *     primary 并捕获非 Feign 真 bean 名（{@code LocalWorkflowRuntimeClient}）供 DELEGATE 透传；
 *     装配形态再漂移=披露差异 TL 裁决；
 * A-2 apply 失败路径响应后任务行可观测态=PENDING + attempts&gt;=1（委派单钉死事实 3）——
 *     「PENDING, attempts=0」是事务内初始态，HTTP 响应写出前 afterCommit 已同步认领（请求线程
 *     同步执行），黑盒不可观测；零尝试初始态经 AC-6 grace 机制用例承载（正/负例双向）；
 * A-3 重试轮次全部由 {@code LeaveStartRetryJob.scanAndRetry()} 直驱（契约钉面 public 无参）。
 *     【A-9 round-1 修复（TL 裁决，主线程实施）】初版无 TenantContext 直调 scanAndRetry 认领
 *     不生效（FlexTenantFactory 缺上下文返回 UNBOUND_TENANT 哨兵：读恒空集、写被守卫拒——
 *     生产态 @Scheduled 线程补偿死面）。修复=捞取经 {@code @IgnoreTenant} 门面
 *     （LeaveStartTaskScanGateway）跨租户 + job 逐任务绑定行租户承载回收/认领/执行；
 *     本类 driveScan 现为**无上下文直驱**（回归证明形态，缺陷回潮即红）；
 * A-4 grace/租约观测面=V2 表审计列（created_at/updated_at 为唯一可钉锚，表无独立 lease 列）：
 *     正例将两列均回拨 60s+、负例保持新鲜（正/负例双向覆盖，兼容 grace 锚定 created_at 或
 *     next_retry_at 两种实现形态）；租约 2min / grace 10s 缺省值=契约确认③；
 * A-5 EXHAUSTED「结构化 warn 留痕」的日志面（logger 名/文案未钉死）不作日志断言，以 DB 留痕
 *     （status=EXHAUSTED + last_error 非空）承载，日志面归属如实披露于映射表；
 * A-6 leave_request 无 business_key 列（V1 钉面）；重试启动的 businessKey 由实现自 leave 行推导
 *     （实测=leaveId 数字串），黑盒不断言 wf 侧 businessKey 面，仅以 instance_id 回写+任务 DONE
 *     为锚；attempts 语义=失败次数计数（实测：首启成功 attempts=0，每轮失败 +1——契约状态机
 *     「PENDING(attempts=0 初始)→失败 PENDING(attempts>=1)」一致的观测面）；
 * A-7 时序事实（委派单钉死事实 3）：afterCommit 在请求线程同步执行、发生于 HTTP 响应写出之前
 *     ——成功路径 apply 响应后 JDBC 查 instance_id 即已回写（非空）；
 * A-8 teardown 清 leave_request/leave_start_task 行集（范本 @AfterEach 形制）；wf_* 行按
 *     businessKey/实例 id 天然隔离不清（逐用例清 wf_process_definition 会破坏试点部署缓存，
 *     范本实证注记）；类隔离库随类销毁，无跨类残留面。
 * A-10 环境噪声（与本批无关，如实登记）：单体上下文 SchedulerTickJob 每秒报 ShedLock
 *     LockProvider NoUniqueBeanDefinitionException（lockProvider/schedulerLockProvider 双
 *     provider 歧义，platform-scheduler 既有面），不影响 leave 域行为断言。</p>
 */
@SpringBootTest(classes = MonolithApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(REQ20261128092210LeaveStartCompensationIntegrationTest.WorkflowStubConfig.class)
class REQ20261128092210LeaveStartCompensationIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** B 端登录种子密码（MonolithContextSmokeTest 同款环境键）。 */
    private static final String SEED_SUPERADMIN_PASSWORD = "Monolith@123456";

    private static final String LEAVE_REQUESTS_URL = "/admin/v1/leave/requests";

    /** 契约确认③钉死缺省：重试上限 5 次尝试 / 指数退避 1s→2s→4s→8s→16s / grace 10s / 租约 2min。 */
    private static final int MAX_ATTEMPTS = 5;
    private static final long GRACE_MS = 10_000L;
    private static final long LEASE_MS = 120_000L;

    /** 零 Docker 嵌入式底座（共享 PG 进程 + 随机端口；本类独占隔离库，见 flex-test-assembly 口径 6）。 */
    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        EmbeddedRedisSupport redis = EmbeddedRedisSupport.shared();
        redis.flushAll();
        registry.add("spring.datasource.url", () -> pg.jdbcUrl(
                "acme_monolith_leave_comp",
                "platform_auth,platform_system,platform_infra,platform_scheduler,platform_workflow,business_leave"));
        registry.add("spring.datasource.username", pg::username);
        registry.add("spring.datasource.password", pg::password);
        registry.add("spring.data.redis.host", redis::host);
        registry.add("spring.data.redis.port", redis::port);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("ACME_SEED_SUPERADMIN_PASSWORD", () -> SEED_SUPERADMIN_PASSWORD);
        registry.add("ACME_SEED_DEMO_ADMIN_PASSWORD", () -> "MonolithDemo@123");
        registry.add("ACME_SEED_DEMO_OPERATOR_PASSWORD", () -> "MonolithDemo@456");
        registry.add("acme.storage.local.root-dir", () -> tempStorageRoot().toString());
        registry.add("ACME_USER_TOKEN_HMAC_SECRET", () -> "leave-comp-user-token-secret-0123456789abcdef");
        registry.add("ACME_SECURITY_TOKEN_HMAC_SECRET", () -> "leave-comp-security-token-secret-0123456789");
    }

    private static java.nio.file.Path tempStorageRoot() {
        try {
            return Files.createTempDirectory("acme-leave-comp-storage");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("创建存储根目录失败", ex);
        }
    }

    // ---------- 失败注入缝（A-1）：@TestConfiguration + @Primary 覆盖 WorkflowRuntimeClient ----------

    /**
     * WorkflowRuntimeClient 桩：THROW 态对启动调用抛异常（全捕获不反噬→补偿面）；
     * DELEGATE 态透传单体 Local 短路真 bean（恢复成功路径，AC-10 全链与认领回写用例）。
     * startProcessInstance 调用计数供并发认领「恰一执行者」断言（AC-8）。
     */
    static class WorkflowRuntimeStub implements WorkflowRuntimeClient {

        static final AtomicReference<Boolean> DELEGATE = new AtomicReference<>(false);
        static final AtomicInteger START_CALLS = new AtomicInteger();

        private final ConfigurableApplicationContext context;
        private volatile WorkflowRuntimeClient real;

        WorkflowRuntimeStub(ConfigurableApplicationContext context) {
            this.context = context;
        }

        private WorkflowRuntimeClient real() {
            WorkflowRuntimeClient found = real;
            if (found == null) {
                String beanName = WorkflowStubConfig.REAL_CLIENT_BEAN_NAME;
                assertThat(beanName)
                        .as("应定位到单体 Local 短路真 WorkflowRuntimeClient bean——装配披露差异 TL 裁决")
                        .isNotNull();
                found = context.getBean(beanName, WorkflowRuntimeClient.class);
                real = found;
            }
            return found;
        }

        @Override
        public ContractResult<Long> startProcessInstance(InternalProcessInstanceStartRequest request) {
            START_CALLS.incrementAndGet();
            if (!Boolean.TRUE.equals(DELEGATE.get())) {
                throw new IllegalStateException("tester-simulated-workflow-outage");
            }
            return real().startProcessInstance(request);
        }

        @Override
        public ContractResult<WorkflowInstanceDetailView> processInstanceDetail(Long instanceId) {
            if (!Boolean.TRUE.equals(DELEGATE.get())) {
                throw new IllegalStateException("tester-simulated-workflow-outage");
            }
            return real().processInstanceDetail(instanceId);
        }

        @Override
        public ContractResult<Void> writeVariables(Long instanceId, WorkflowVariablesWriteRequest request) {
            if (!Boolean.TRUE.equals(DELEGATE.get())) {
                throw new IllegalStateException("tester-simulated-workflow-outage");
            }
            return real().writeVariables(instanceId, request);
        }
    }

    @TestConfiguration
    static class WorkflowStubConfig {

        /** BDRPP 阶段捕获的非桩真 bean 名（按 beanClass 可赋值判定，天然排除 Feign 工厂定义）。 */
        static volatile String REAL_CLIENT_BEAN_NAME;

        @Bean
        @Primary
        WorkflowRuntimeClient stubWorkflowRuntimeClient(ConfigurableApplicationContext applicationContext) {
            return new WorkflowRuntimeStub(applicationContext);
        }

        /**
         * 单体 local 适配器自带 @Primary，桩再标 @Primary 会触发多 primary 注入歧义——
         * 此处按「产物类型可赋值」判定（@Bean 方法定义的 beanClassName 是配置类，须经
         * getType 解析产物类型）降级其余 WorkflowRuntimeClient bean 定义的 primary 标记，
         * 使桩成为唯一 primary；同时捕获非桩真 bean 名供 DELEGATE 透传。
         */
        @Bean
        static BeanFactoryPostProcessor demoteOtherRuntimeClientPrimaries() {
            return beanFactory -> {
                for (String name : beanFactory.getBeanDefinitionNames()) {
                    if ("stubWorkflowRuntimeClient".equals(name)) {
                        continue;
                    }
                    boolean feignFactoryTyped =
                            "com.acme.platform.workflow.api.WorkflowRuntimeClient".equals(name);
                    Class<?> producedType = null;
                    try {
                        producedType = beanFactory.getType(name);
                    } catch (RuntimeException | NoClassDefFoundError ignored) {
                        // 类型不可解析（惰性工厂等）：仅按名称兜底判定
                    }
                    boolean delegateTyped =
                            producedType != null && WorkflowRuntimeClient.class.isAssignableFrom(producedType);
                    // DELEGATE 透传只认非 Feign 工厂的真实现（Feign 工厂 bean 名=接口 FQCN，
                    // 单体无服务发现不可用；local 短路适配器为唯一合法透传目标）
                    if (delegateTyped && !feignFactoryTyped) {
                        REAL_CLIENT_BEAN_NAME = name;
                    }
                    if (delegateTyped || feignFactoryTyped) {
                        beanFactory.getBeanDefinition(name).setPrimary(false);
                    }
                }
            };
        }
    }

    private void stubThrowing() {
        WorkflowRuntimeStub.DELEGATE.set(false);
        WorkflowRuntimeStub.START_CALLS.set(0);
    }

    private void stubPassThrough() {
        WorkflowRuntimeStub.DELEGATE.set(true);
        WorkflowRuntimeStub.START_CALLS.set(0);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 重试任务驱动缝（A-3）：public 无参扫描方法直调，每调用=一轮扫描认领。 */
    @Autowired
    private LeaveStartRetryJob retryJob;

    /**
     * 直驱一轮重试扫描（A-3；round-1 修复后回归证明）：**无租户上下文直调** {@code scanAndRetry()}
     * ——模拟生产态 @Scheduled 线程；捞取经 {@code @IgnoreTenant} 门面跨租户、回收/认领/执行由
     * job 逐任务绑定行租户上下文，全链自足（round-1 前无上下文直调认领不生效的缺陷即由本驱动
     * 形态锁死防回潮）。
     */
    private void driveScan() {
        retryJob.scanAndRetry();
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据（A-8 范本形制）：只清 leave_request/leave_start_task 行集；
        // wf_* 行按 businessKey/实例 id 天然隔离不清（范本实证注记），隔离库随类销毁。
        try {
            jdbcTemplate.update("delete from " + qualified("leave_start_task"));
        } catch (org.springframework.dao.DataAccessException ignored) {
            // V2 迁移未落地（等实现期）：跳过清理，测试自身会给出明确信号
        }
        try {
            jdbcTemplate.update("delete from " + qualified("leave_request"));
        } catch (org.springframework.dao.DataAccessException ignored) {
            // 表未随实现落地（等实现期）：跳过清理
        }
    }

    // ---------- AC-1：申请落库 APPLYING + 任务行 PENDING 双行原子性 + 响应信封 ----------

    @Test
    void should_atomically_persist_request_and_pending_task_when_apply_committed() throws Exception {
        stubThrowing();
        String token = loginAdmin();

        JsonNode created = applyLeave(token, "leave-comp-atomic-" + SEQ.incrementAndGet());
        ok(created, "申请应成功（启动失败不反噬，响应恒 2xx 成功信封）");
        JsonNode data = created.path("data");
        assertThat(data.path("status").asText()).as("响应 status 应为 APPLYING").isEqualTo("APPLYING");
        assertThat(data.path("instanceId").isNull() || data.path("instanceId").isMissingNode())
                .as("响应 instanceId 应为 null（启动后置，A-2/契约 API）").isTrue();
        long leaveId = idOf(created);

        // 双行原子落库：leave_request(APPLYING) 行与 leave_start_task(PENDING) 行同 commit 在场
        Map<String, Object> leave = leaveRow(leaveId);
        assertThat(leave).as("leave_request 行应原子落库（id=%s）", leaveId).isNotNull();
        assertThat(str(leave, "status")).as("leave 应落 APPLYING").isEqualTo("APPLYING");
        assertThat(leave.get("instance_id")).as("instance_id 应为 null（启动后置）").isNull();

        Map<String, Object> task = taskRow(leaveId);
        assertThat(task).as("leave_start_task 补偿任务行应与单据同事务原子落库（leave_id=%s）", leaveId).isNotNull();
        assertThat(str(task, "status")).as("任务行应处 PENDING（A-2：响应后可观测态）").isEqualTo("PENDING");
        assertThat(num(task, "leave_id")).as("任务行应回链本单").isEqualTo(leaveId);
        assertThat(str(task, "tenant_id"))
                .as("任务行 tenant_id 应与单据一致（V2 @TenantId）").isEqualTo(str(leave, "tenant_id"));
    }

    // ---------- AC-3：启动失败全捕获不反噬 + 补偿登记 + 指数退避 ----------

    @Test
    void should_register_backoff_and_keep_intermediate_when_aftercommit_start_fails() throws Exception {
        stubThrowing();
        String token = loginAdmin();

        JsonNode created = applyLeave(token, "leave-comp-backoff-" + SEQ.incrementAndGet());
        ok(created, "启动失败后响应仍 2xx 成功信封（异常全捕获不反噬，恒不 5xx）");
        assertThat(created.path("data").path("status").asText()).as("单据保持 APPLYING 非死态").isEqualTo("APPLYING");
        long leaveId = idOf(created);
        assertThat(created.path("data").path("instanceId").isNull()
                || created.path("data").path("instanceId").isMissingNode())
                .as("响应 instanceId 应为 null").isTrue();

        // 失败登记面：任务行 PENDING + attempts>=1 + next_retry_at 指数退避（未来时刻）+ last_error 留痕
        Map<String, Object> task = taskRow(leaveId);
        assertThat(task).as("补偿任务行应存在").isNotNull();
        assertThat(str(task, "status")).as("失败后任务行应回 PENDING").isEqualTo("PENDING");
        assertThat(num(task, "attempts")).as("首次失败后 attempts 应>=1").isGreaterThanOrEqualTo(1);
        Timestamp firstRetryAt = (Timestamp) task.get("next_retry_at");
        assertThat(firstRetryAt).as("next_retry_at 应非空（指数退避调度）").isNotNull();
        assertThat(firstRetryAt.after(Timestamp.from(Instant.now())))
                .as("next_retry_at 应在未来（退避窗口）：%s", firstRetryAt).isTrue();
        assertThat(task.get("last_error")).as("last_error 应留痕").isNotNull();
        assertThat(String.valueOf(task.get("last_error"))).isNotBlank();
        assertLeaveIntermediateUnstarted(leaveId);

        // 退避轮次驱动：到期→driveScan→再失败→下一退避刻度后移（gap≈2s 契约 1s→2s 序推）
        assertThat(awaitRetryDue(leaveId, 15_000)).as("首轮退避（1s 量级）应到期").isTrue();
        driveScan();
        Map<String, Object> after = taskRow(leaveId);
        assertThat(after).as("重试轮次后任务行应存在").isNotNull();
        assertThat(str(after, "status")).as("启动持续失败（桩恒抛）任务行应回 PENDING").isEqualTo("PENDING");
        assertThat(num(after, "attempts")).as("每轮扫描认领恰计一次尝试").isEqualTo(num(task, "attempts") + 1);
        Timestamp secondRetryAt = (Timestamp) after.get("next_retry_at");
        assertThat(secondRetryAt).as("次轮 next_retry_at 应非空").isNotNull();
        long gapMs = secondRetryAt.getTime() - firstRetryAt.getTime();
        assertThat(gapMs)
                .as("相邻 next_retry_at 间距应呈指数退避增长（契约 1s→2s；实测 gap=%sms）", gapMs)
                .isBetween(1_500L, 15_000L);
        assertThat(String.valueOf(after.get("last_error"))).as("last_error 应持续留痕").isNotBlank();
        assertLeaveIntermediateUnstarted(leaveId);
    }

    // ---------- AC-5：重试耗尽 EXHAUSTED，不落终态 ----------

    @Test
    void should_mark_task_exhausted_and_keep_leave_intermediate_when_attempts_exhausted() throws Exception {
        stubThrowing();
        String token = loginAdmin();

        JsonNode created = applyLeave(token, "leave-comp-exhaust-" + SEQ.incrementAndGet());
        ok(created, "申请应成功（启动失败转入补偿面）");
        long leaveId = idOf(created);

        // 轮次驱动：每轮等 next_retry_at 到期→driveScan（A-3 直驱；后台调度无上下文不认领无竞态）
        for (int round = 0; round < 10 && !"EXHAUSTED".equals(taskStatus(leaveId)); round++) {
            assertThat(awaitRetryDue(leaveId, 30_000))
                    .as("第 %d 轮退避应到期（指数 1s→2s→4s→8s 序推）", round + 1).isTrue();
            driveScan();
        }

        Map<String, Object> task = taskRow(leaveId);
        assertThat(task).as("耗尽后任务行应存在").isNotNull();
        assertThat(str(task, "status")).as("重试轮次达上限应置 EXHAUSTED（A-5：DB 留痕锚）")
                .isEqualTo("EXHAUSTED");
        assertThat(num(task, "attempts")).as("尝试次数应恰为上限 %d（直驱无并发）", MAX_ATTEMPTS)
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(task.get("last_error")).as("EXHAUSTED 应保留末次错误留痕").isNotNull();
        assertThat(String.valueOf(task.get("last_error"))).isNotBlank();

        // 不落终态：leave 保持 APPLYING、instance_id 不回写、不写 APPROVING/DONE 等任何前向态
        assertLeaveIntermediateUnstarted(leaveId);

        // EXHAUSTED 为任务终态：再次扫描不得再驱动（attempts 不增、状态不回 PENDING）
        int attemptsAtExhaustion = (int) num(task, "attempts");
        driveScan();
        assertThat(taskStatus(leaveId)).as("EXHAUSTED 后不应再被认领").isEqualTo("EXHAUSTED");
        assertThat(attemptsOf(leaveId)).as("EXHAUSTED 后 attempts 不应增长").isEqualTo(attemptsAtExhaustion);
    }

    // ---------- AC-2：afterCommit 启动成功回写 instance_id + 任务 DONE ----------

    @Test
    void should_writeback_instance_and_done_when_aftercommit_start_succeeds() throws Exception {
        stubPassThrough();
        String token = loginAdmin();

        JsonNode created = applyLeave(token, "leave-comp-happy-" + SEQ.incrementAndGet());
        ok(created, "申请应成功");
        long leaveId = idOf(created);
        assertThat(created.path("data").path("status").asText()).as("响应 status=APPLYING").isEqualTo("APPLYING");
        assertThat(created.path("data").path("instanceId").isNull()
                || created.path("data").path("instanceId").isMissingNode())
                .as("响应 instanceId 恒为 null（启动后置；回写经详情可读，契约 API）").isTrue();

        // A-7：afterCommit 请求线程同步执行于响应写出前——响应后 instance_id 即已回写
        assertThat(await(5_000, () -> instanceIdOf(leaveId) > 0))
                .as("afterCommit 启动成功后 instance_id 应回写（A-7）").isTrue();
        long instanceId = instanceIdOf(leaveId);

        Map<String, Object> task = taskRow(leaveId);
        assertThat(task).as("补偿任务行应存在").isNotNull();
        assertThat(str(task, "status")).as("启动成功任务行应置 DONE").isEqualTo("DONE");
        assertThat(num(task, "attempts")).as("首次启动成功无失败登记（attempts 保持初始 0，失败计数语义）")
                .isEqualTo(0);
        assertThat(num(task, "leave_id")).isEqualTo(leaveId);
        assertThat(taskCountFor(leaveId)).as("一单一任务（uk_leave_start_task_leave）").isEqualTo(1);

        assertLeaveStatus(leaveId, "APPLYING", "启动成功不应前推单据状态（推进归审批/备案面）");

        // 实例真实启动：wf_process_instance 行存在且 RUNNING
        assertThat(await(5_000, () -> workflowInstanceStatus(instanceId) != null))
                .as("流程实例应已启动").isTrue();
        assertThat(workflowInstanceStatus(instanceId)).as("实例应处 RUNNING（试点单链首站=审批 UserTask）")
                .isEqualTo("RUNNING");

        // 回写后详情可读（契约 API：GET /requests/{id}）
        JsonNode detail = bodyOf(get(LEAVE_REQUESTS_URL + "/" + leaveId, token));
        ok(detail, "详情应可读");
        assertThat(detail.path("data").path("id").asLong()).as("详情应为本单").isEqualTo(leaveId);
        assertThat(detail.path("data").path("instanceId").asLong())
                .as("详情 instanceId 应为异步回写值（可空字段回写后非空）").isEqualTo(instanceId);
    }

    // ---------- AC-4：重试认领回写（非并发面；并发面见 AC-8 用例） ----------

    @Test
    void should_claim_retry_writeback_instance_and_done_when_retry_due() throws Exception {
        stubPassThrough();
        String token = loginAdmin();
        long leaveId = applyRealLeave(token, "leave-comp-retry-");
        craftRetryDueRow(leaveId, 1);

        driveScan();

        assertThat(await(10_000, () -> "DONE".equals(taskStatus(leaveId))))
                .as("到期 PENDING 行应被认领并启动成功置 DONE，实际=%s", taskStatus(leaveId)).isTrue();
        Map<String, Object> task = taskRow(leaveId);
        assertThat(num(task, "attempts")).as("成功路径无失败登记（attempts 保持失败计数）")
                .isGreaterThanOrEqualTo(1);
        assertThat(instanceIdOf(leaveId)).as("重试认领成功应回写 instance_id").isPositive();
        assertLeaveStatus(leaveId, "APPLYING", "重试管道只回写 instance_id，不前推单据状态机");
    }

    // ---------- AC-6：零尝试行 grace 期后补投（正例）+ grace 期内不捞起（负例） ----------

    @Test
    void should_pick_zero_attempt_leftover_after_grace_when_scan_runs() throws Exception {
        stubPassThrough();
        String token = loginAdmin();
        long leaveId = applyRealLeave(token, "leave-comp-grace-pos-");
        // 模拟「提交后、内联启动前崩溃」遗留：PENDING + attempts=0；created_at/next_retry_at
        // 均回拨至 grace（10s 缺省）之外（A-4 双锚回拨，兼容两种 grace 实现形态）
        craftTaskRow(leaveId, "PENDING", 0, past(GRACE_MS * 6), null, past(GRACE_MS * 6), past(GRACE_MS * 6));
        craftLeaveUnstarted(leaveId);

        driveScan();

        assertThat(await(10_000, () -> "DONE".equals(taskStatus(leaveId))))
                .as("超过 grace 期的零尝试遗留行应被捞起补投并置 DONE，实际=%s", taskStatus(leaveId)).isTrue();
        assertThat(instanceIdOf(leaveId)).as("补投成功应回写 instance_id（防丢失）").isPositive();
        assertThat(attemptsOf(leaveId)).as("补投一次成功无失败登记（attempts 保持 0）").isEqualTo(0);
        assertLeaveStatus(leaveId, "APPLYING", "补投不前推单据状态机");
    }

    @Test
    void should_skip_zero_attempt_leftover_within_grace_when_scan_runs() throws Exception {
        stubPassThrough();
        String token = loginAdmin();
        long leaveId = applyRealLeave(token, "leave-comp-grace-neg-");
        // 新鲜零尝试行（created_at=now、next_retry_at 未来）：grace 期内扫描不得捞起
        //（防与 afterCommit 内联启动双投的第一防线）
        craftTaskRow(leaveId, "PENDING", 0, future(GRACE_MS * 6), null, Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        craftLeaveUnstarted(leaveId);

        driveScan();

        Map<String, Object> task = taskRow(leaveId);
        assertThat(task).as("任务行应存在").isNotNull();
        assertThat(str(task, "status")).as("grace 期内零尝试行不应被认领").isEqualTo("PENDING");
        assertThat(num(task, "attempts")).as("grace 期内不应计入尝试").isEqualTo(0);
        assertThat(instanceIdOf(leaveId)).as("grace 期内不应启动流程").isEqualTo(-1L);
    }

    // ---------- AC-7：RUNNING 租约回收自愈（正例）+ 租约期内不回收（负例） ----------

    @Test
    void should_recycle_expired_lease_running_row_when_scan_runs() throws Exception {
        stubPassThrough();
        String token = loginAdmin();
        long leaveId = applyRealLeave(token, "leave-comp-lease-pos-");
        // 模拟执行器崩溃租约悬挂：RUNNING 且 updated_at 早于租约（2min 缺省）之外
        craftTaskRow(leaveId, "RUNNING", 1, past(LEASE_MS * 2), null, past(LEASE_MS * 2), past(LEASE_MS + 60_000L));
        craftLeaveUnstarted(leaveId);

        driveScan();

        assertThat(await(10_000, () -> "DONE".equals(taskStatus(leaveId))))
                .as("超租约 RUNNING 行应被回收重投并最终 DONE，实际=%s", taskStatus(leaveId)).isTrue();
        assertThat(instanceIdOf(leaveId)).as("回收重投成功应回写 instance_id（自愈）").isPositive();
        assertThat(attemptsOf(leaveId)).as("回收再认领应累计尝试").isGreaterThanOrEqualTo(1);
        assertLeaveStatus(leaveId, "APPLYING", "回收重投不前推单据状态机");
    }

    @Test
    void should_hold_running_row_within_lease_when_scan_runs() throws Exception {
        stubPassThrough();
        String token = loginAdmin();
        long leaveId = applyRealLeave(token, "leave-comp-lease-neg-");
        // 租约期内 RUNNING 行（updated_at=now）：扫描不得回收（执行器在场的正常租约持有面）
        craftTaskRow(leaveId, "RUNNING", 1, Timestamp.from(Instant.now()), null,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        craftLeaveUnstarted(leaveId);
        int attemptsBefore = attemptsOf(leaveId);

        driveScan();

        Map<String, Object> task = taskRow(leaveId);
        assertThat(str(task, "status")).as("租约期内 RUNNING 行不应被回收").isEqualTo("RUNNING");
        assertThat(num(task, "attempts")).as("租约期内不应重复认领").isEqualTo(attemptsBefore);
        assertThat(instanceIdOf(leaveId)).as("租约期内不应重复启动").isEqualTo(-1L);
    }

    // ---------- AC-8：并发认领恰一执行者（乐观锁败者跳过） ----------

    @Test
    void should_claim_by_exactly_one_executor_when_concurrent_scans_race() throws Exception {
        stubPassThrough();
        String token = loginAdmin();
        long leaveId = applyRealLeave(token, "leave-comp-race-");
        craftRetryDueRow(leaveId, 1);
        int attemptsBefore = attemptsOf(leaveId);
        WorkflowRuntimeStub.START_CALLS.set(0);

        // 双节点并发认领模拟：两线程同时直调同一扫描方法（分布式多实例同任务行竞态）
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Runnable worker = () -> {
            try {
                startGate.await(10, TimeUnit.SECONDS);
                driveScan();
                finished.incrementAndGet();
            } catch (Throwable ex) {
                unexpected.compareAndSet(null, ex);
            }
        };
        Thread nodeA = new Thread(worker, "leave-comp-node-a");
        Thread nodeB = new Thread(worker, "leave-comp-node-b");
        nodeA.start();
        nodeB.start();
        startGate.countDown();
        nodeA.join(30_000);
        nodeB.join(30_000);
        assertThat(unexpected.get())
                .as("并发扫描不应外抛（乐观锁败者应跳过收敛）：%s", unexpected.get()).isNull();
        assertThat(finished.get()).as("两执行者均应完成扫描").isEqualTo(2);

        assertThat(await(10_000, () -> "DONE".equals(taskStatus(leaveId))))
                .as("恰一执行者应启动成功置 DONE，实际=%s", taskStatus(leaveId)).isTrue();
        assertThat(WorkflowRuntimeStub.START_CALLS.get())
                .as("恰一执行者应调 startProcessInstance（并发竞态恰一次启动）").isEqualTo(1);
        assertThat(attemptsOf(leaveId)).as("恰一次认领成功（无失败登记，attempts 不变）").isEqualTo(attemptsBefore);
        assertThat(instanceIdOf(leaveId)).as("instance_id 恰回写一次（uk_leave_request_instance_uid 兜底不触发）")
                .isPositive();
        assertLeaveStatus(leaveId, "APPLYING", "并发认领不前推单据状态机");
    }

    // ---------- AC-10：失败入补偿→重试回写→DONE 全链（嵌入式 PG 实跑非 SKIP） ----------

    @Test
    void should_complete_compensation_chain_when_retry_succeeds_after_start_failure() throws Exception {
        stubThrowing();
        String token = loginAdmin();
        String businessKey = "leave-comp-chain-" + SEQ.incrementAndGet();

        // ① 启动失败→失败入补偿：2xx + APPLYING + instance_id 空 + PENDING(attempts>=1)
        JsonNode created = applyLeave(token, businessKey);
        ok(created, "启动失败不反噬（恒 2xx）");
        long leaveId = idOf(created);
        Map<String, Object> task = taskRow(leaveId);
        assertThat(task).as("失败后补偿任务行应登记").isNotNull();
        assertThat(str(task, "status")).isEqualTo("PENDING");
        assertThat(num(task, "attempts")).isGreaterThanOrEqualTo(1);
        assertThat(instanceIdOf(leaveId)).isEqualTo(-1L);
        assertLeaveIntermediateUnstarted(leaveId);

        // ② 恢复启动成功：桩切透传→到期→driveScan 认领→真实启动→回写 instance_id→DONE
        stubPassThrough();
        assertThat(awaitRetryDue(leaveId, 15_000)).as("首轮退避应到期").isTrue();
        driveScan();

        assertThat(await(10_000, () -> "DONE".equals(taskStatus(leaveId))))
                .as("补偿重试成功后任务应置 DONE，实际=%s", taskStatus(leaveId)).isTrue();
        long instanceId = instanceIdOf(leaveId);
        assertThat(instanceId).as("补偿重试成功应回写 instance_id").isPositive();
        assertThat(workflowInstanceStatus(instanceId))
                .as("重试启动的实例应真实存在并 RUNNING").isEqualTo("RUNNING");
        assertLeaveStatus(leaveId, "APPLYING", "补偿链不前推单据状态机");

        // ③ 回写后详情可读（全链收敛：失败入补偿→重试回写 instanceId→任务 DONE）
        JsonNode detail = bodyOf(get(LEAVE_REQUESTS_URL + "/" + leaveId, token));
        ok(detail, "回写后详情应可读");
        assertThat(detail.path("data").path("instanceId").asLong()).isEqualTo(instanceId);
    }

    // ---------- V2 迁移索引钉面（AC-8 双启动兜底 + 契约设计定案） ----------

    @Test
    void should_guard_double_start_with_compensation_indexes_when_v2_migration_applied() {
        // uk_leave_start_task_leave：一单一任务唯一索引
        String taskUnique = indexDef("uk_leave_start_task_leave");
        assertThat(taskUnique).as("uk_leave_start_task_leave 应落位（V2 幂等 IF NOT EXISTS）").isNotNull();
        assertThat(taskUnique).contains("UNIQUE").contains("(leave_id)");
        // idx_leave_start_task_scan：扫描专属索引（status, next_retry_at）
        String scanIndex = indexDef("idx_leave_start_task_scan");
        assertThat(scanIndex).as("idx_leave_start_task_scan 应落位").isNotNull();
        assertThat(scanIndex).contains("(status, next_retry_at)").doesNotContain("UNIQUE");
        // uk_leave_request_instance_uid：部分唯一索引（双启动最后兜底，防任务认领竞态之外错绑）
        String instanceUnique = indexDef("uk_leave_request_instance_uid");
        assertThat(instanceUnique).as("uk_leave_request_instance_uid 应落位").isNotNull();
        assertThat(instanceUnique).contains("UNIQUE").contains("(instance_id)")
                .contains("WHERE").contains("instance_id IS NOT NULL");
    }

    // ---------- 用例支撑（范本形制照抄：HTTP 全过滤链 + JDBC 夹具 + await 轮询） ----------

    private String loginAdmin() throws Exception {
        HttpResponse<String> login = postJson(null, "/admin/v1/auth/tokens", """
                {"tenantCode":"default","username":"admin","password":"%s"}
                """.formatted(SEED_SUPERADMIN_PASSWORD));
        assertThat(login.statusCode()).as("登录应 200：%s", login.body()).isEqualTo(200);
        String body = login.body();
        assertThat(body).contains("\"success\":true");
        String token = extractJsonStringField(body, "accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 申请（合法体）并断言成功信封（业务成功断言统一信封口径，勿断言 4xx）。 */
    private JsonNode applyLeave(String token, String businessKey) throws Exception {
        return bodyOf(postJson(token, LEAVE_REQUESTS_URL, """
                {"leaveType":"annual","reason":"tester-comp",
                 "startTime":"2026-12-01T09:00:00","endTime":"2026-12-02T18:00:00",
                 "businessKey":"%s"}
                """.formatted(businessKey)));
    }

    /** 真实申请（DELEGATE 态 afterCommit 启动成功）→ 返回 leaveId（构造后续夹具的基座）。 */
    private long applyRealLeave(String token, String businessKeyPrefix) throws Exception {
        JsonNode created = applyLeave(token, businessKeyPrefix + SEQ.incrementAndGet());
        ok(created, "基座申请应成功（DELEGATE 态 afterCommit 启动成功）");
        long leaveId = idOf(created);
        assertThat(await(5_000, () -> instanceIdOf(leaveId) > 0))
                .as("基座单 afterCommit 应回写 instance_id（A-7）").isTrue();
        return leaveId;
    }

    /** 将任务行改写为「重试到期」形态（模拟一轮失败遗留；JDBC 直改不经实现过滤面）。 */
    private void craftRetryDueRow(long leaveId, int attempts) {
        craftTaskRow(leaveId, "PENDING", attempts, past(1_000L), "tester-simulated-failure",
                past(3_000L), past(2_000L));
        craftLeaveUnstarted(leaveId);
    }

    private void craftTaskRow(long leaveId, String status, int attempts, Timestamp nextRetryAt,
                              String lastError, Timestamp createdAt, Timestamp updatedAt) {
        int updated = jdbcTemplate.update(
                "update " + qualified("leave_start_task")
                        + " set status = ?, attempts = ?, next_retry_at = ?, last_error = ?,"
                        + " created_at = ?, updated_at = ? where leave_id = ?",
                status, attempts, nextRetryAt, lastError, createdAt, updatedAt, leaveId);
        assertThat(updated).as("任务行夹具改写应命中（leave_id=%s）", leaveId).isEqualTo(1);
    }

    private void craftLeaveUnstarted(long leaveId) {
        int updated = jdbcTemplate.update(
                "update " + qualified("leave_request") + " set status = 'APPLYING', instance_id = null where id = ?",
                leaveId);
        assertThat(updated).as("leave 行夹具改写应命中（id=%s）", leaveId).isEqualTo(1);
    }

    private void assertLeaveIntermediateUnstarted(long leaveId) {
        assertLeaveStatus(leaveId, "APPLYING", "leave 应保持中间态（不落死态/不写终态）");
        assertThat(instanceIdOf(leaveId)).as("instance_id 不应回写").isEqualTo(-1L);
    }

    private void assertLeaveStatus(long leaveId, String expected, String desc) {
        assertThat(leaveStatus(leaveId)).as("%s（leaveId=%s）", desc, leaveId).isEqualTo(expected);
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String bearerToken, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode bodyOf(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    /** data.id 宽容解析（Long wire=字符串 FR-024，范本同款）。 */
    private long idOf(JsonNode resp) {
        JsonNode data = resp.path("data");
        JsonNode id = data.has("id") ? data.path("id") : data;
        return id.asLong();
    }

    private static String extractJsonStringField(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        assertThat(matcher.find()).as("响应应包含字段 %s：%s", field, json).isTrue();
        return matcher.group(1);
    }

    // ---------- DB 夹具读取（qualified 经 information_schema 定位，范本同款） ----------

    private String qualified(String tableName) {
        List<String> schemas = jdbcTemplate.queryForList(
                "select table_schema from information_schema.tables "
                        + "where table_name = ? and table_schema not in ('pg_catalog','information_schema')",
                String.class, tableName);
        return schemas.isEmpty() ? tableName : schemas.get(0) + "." + tableName;
    }

    private Map<String, Object> leaveRow(long leaveId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from " + qualified("leave_request") + " where id = ?", leaveId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> taskRow(long leaveId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from " + qualified("leave_start_task") + " where leave_id = ?", leaveId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long taskCountFor(long leaveId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from " + qualified("leave_start_task") + " where leave_id = ?",
                Long.class, leaveId);
        return count == null ? 0L : count;
    }

    private String leaveStatus(long leaveId) {
        Map<String, Object> row = leaveRow(leaveId);
        return row == null ? null : str(row, "status");
    }

    private long instanceIdOf(long leaveId) {
        Map<String, Object> row = leaveRow(leaveId);
        return row == null || row.get("instance_id") == null ? -1L : num(row, "instance_id");
    }

    private String taskStatus(long leaveId) {
        Map<String, Object> row = taskRow(leaveId);
        return row == null ? null : str(row, "status");
    }

    private int attemptsOf(long leaveId) {
        Map<String, Object> row = taskRow(leaveId);
        return row == null ? -1 : (int) num(row, "attempts");
    }

    private Timestamp nextRetryAtOf(long leaveId) {
        Map<String, Object> row = taskRow(leaveId);
        return row == null ? null : (Timestamp) row.get("next_retry_at");
    }

    private boolean awaitRetryDue(long leaveId, long timeoutMs) {
        return await(timeoutMs, () -> {
            Timestamp nextRetryAt = nextRetryAtOf(leaveId);
            return nextRetryAt != null && !nextRetryAt.after(Timestamp.from(Instant.now()));
        });
    }

    private String workflowInstanceStatus(long instanceId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "select status from " + qualified("wf_process_instance") + " where id = ?",
                    String.class, instanceId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (org.springframework.dao.DataAccessException ex) {
            return null;
        }
    }

    private String indexDef(String indexName) {
        List<String> defs = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where indexname = ?", String.class, indexName);
        return defs.isEmpty() ? null : defs.get(0);
    }

    private static long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static String str(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : String.valueOf(value);
    }

    private static Timestamp past(long msAgo) {
        return Timestamp.from(Instant.now().minusMillis(msAgo));
    }

    private static Timestamp future(long msAhead) {
        return Timestamp.from(Instant.now().plusMillis(msAhead));
    }

    private static boolean await(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
