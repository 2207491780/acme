package com.acme.platform.scheduler;

import com.acme.common.context.Channel;
import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261127-235505 B2a（P1-01）scheduler DELETE 注销端点——注册→注销→再注销幂等
 * 集成契约测试（零 Docker 嵌入式 PG 底座：{@code EmbeddedPostgresSupport.shared()} 独占库
 * acme_sched_req235505；真实 MVC + internal token，装配口径照抄既有
 * {@code REQ20261121071002SchedulerCoreIntegrationTest} 先例；无执行器桩：onceFireAt=now+1h
 * 不会到点触发下发，无需 discovery 实例表）。
 *
 * <p>AC→用例映射：</p>
 * <ul>
 *   <li>AC-1：注册一次性任务 → DELETE（internal token）→ HTTP 200 + Result.success；
 *       「软删查无」以确认⑦黑盒代理断言承载：同 jobKey 再注册成功（若未删将得
 *       platform-scheduler-01-0001 撞键错误信封）。「时间轮移除」无 HTTP 观测面，不设断言。</li>
 *   <li>AC-2：从未注册的 jobKey → DELETE → HTTP 200 + success=true（幂等收敛，
 *       不抛 NOT_FOUND、无任何错误信封）。</li>
 *   <li>AC-4：注册→注销→再注销全链三步全 success（落本新增独立测试类）。</li>
 * </ul>
 *
 * <p>口径：成功=HTTP 200 且信封 success=true、code="0"（不断言 data）；jobKey 一律带
 * req235505 前缀；teardown 对创建过的 jobKey 再发 DELETE 收尾 + 独立库物理清残留行。</p>
 */
@SpringBootTest(classes = PlatformSchedulerApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-scheduler-it-secret-0123456789"
})
class REQ20261127235505SchedulerJobDeregisterIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String INTERNAL_JOBS_URL = "/internal/v1/scheduler/jobs";

    /** 协议真实值（一次性任务注册体）：workflow 应用 + 定时器回调 handler。 */
    private static final String APP_NAME = "platform-workflow";
    private static final String HANDLER = "workflow-timer-fire";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        registry.add("spring.datasource.url", () -> pg.jdbcUrl("acme_sched_req235505", "public"));
        registry.add("spring.datasource.username", pg::username);
        registry.add("spring.datasource.password", pg::password);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跨域权限判定桩（照抄先例：单服务测试域以 MockitoBean 桩件承载，缺省放行；
     * 本类只打 internal 面，不触 admin RBAC 判定）。 */
    @MockitoBean
    private AuthorizationClient authorizationClient;

    private MockMvc mvc;

    private final List<String> createdJobKeys = new ArrayList<>();

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
    }

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据：对创建过的 jobKey 再发 DELETE 收尾（幂等软删语义）；
        // 随后独立库物理清残留行（acme_sched_req235505 本类独占，绕 mapper 过滤面）。
        for (String jobKey : createdJobKeys) {
            try {
                internalExchange(delete(INTERNAL_JOBS_URL + "/" + jobKey));
            } catch (Exception cleanupFailure) {
                // 尽力清理，单个失败不阻断其余清理
            }
        }
        createdJobKeys.clear();
        try {
            jdbcTemplate.update("delete from sch_job_execution");
            jdbcTemplate.update("delete from sch_job_definition");
            jdbcTemplate.update("delete from sch_executor_registry");
        } catch (Exception ignored) {
            // 夹具读取失败（上下文已死等）不阻断
        }
    }

    // ---------- 夹具与 HTTP 支撑（照抄先例口径） ----------

    private String nextJobKey() {
        return "wf-timer:req235505-" + SEQ.incrementAndGet();
    }

    /** internal 面（MockMvc 直调 + 内部令牌，先例口径）。 */
    private MvcResult internalExchange(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                "platform-scheduler-it", null, Channel.B, Set.of())))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "it-stub", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 成功信封断言（HTTP 200 + success=true + code="0"；勿断言 data）。 */
    private void assertOk200(MvcResult result, String desc) throws IOException {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as(desc + " 应 HTTP 200，body=%s", body).isEqualTo(200);
        ok(bodyOf(result), desc);
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    /** 一次性任务注册（internal POST 面，先例 AC-12 同款请求体形态；
     * onceFireAt=now+1h 不会到点触发下发，无需执行器桩/实例表）。 */
    private void registerOneShotOk(String jobKey) throws Exception {
        ObjectNode registration = JSON.createObjectNode();
        registration.put("jobKey", jobKey);
        registration.put("appName", APP_NAME);
        registration.put("handler", HANDLER);
        registration.put("onceFireAt", Instant.now().plusSeconds(3600).toString());
        registration.put("param", "{\"req\":\"req235505\"}");
        registration.put("timeoutMs", 60_000L);
        registration.put("retryMaxAttempts", 1);
        registration.put("retryInitialIntervalMs", 100);
        assertOk200(internalExchange(post(INTERNAL_JOBS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(registration))), "一次性任务注册应成功");
        if (!createdJobKeys.contains(jobKey)) {
            createdJobKeys.add(jobKey);
        }
        // 注册应落任务定义（given 成立前提；软删行可能残留于重注册链路，故按存在性断言）
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from sch_job_definition where job_key = ?", Long.class, jobKey);
        assertThat(rows).as("注册应落任务定义（jobKey=%s）", jobKey).isGreaterThanOrEqualTo(1L);
    }

    // ---------- AC-1：注册 → DELETE 成功 → 软删查无（确认⑦代理断言：同键再注册成功） ----------

    @Test
    void should_soft_delete_registered_job_and_allow_reregister_when_deregister_called()
            throws Exception {
        String jobKey = nextJobKey();
        registerOneShotOk(jobKey);

        // 注销：HTTP 200 + Result success=true / code="0"
        assertOk200(internalExchange(delete(INTERNAL_JOBS_URL + "/" + jobKey)), "注销应成功");

        // 软删查无的黑盒代理断言（契约确认⑦）：同 jobKey 再注册成功；
        // 若未真删，将得 platform-scheduler-01-0001 撞键错误信封（此处断 success 即隐式排他）
        registerOneShotOk(jobKey);
    }

    // ---------- AC-2：从未注册 jobKey 注销 → 200 幂等成功，无任何错误信封 ----------

    @Test
    void should_return_success_without_error_envelope_when_deregister_unknown_job_key()
            throws Exception {
        String jobKey = nextJobKey(); // 从未注册
        assertOk200(internalExchange(delete(INTERNAL_JOBS_URL + "/" + jobKey)),
                "注销从未注册 jobKey 应幂等成功");
    }

    // ---------- AC-4：注册 → 注销 → 再注销全链三步全 success ----------

    @Test
    void should_keep_all_steps_success_when_register_deregister_deregister_chain_runs()
            throws Exception {
        String jobKey = nextJobKey();

        // ① 注册
        registerOneShotOk(jobKey);
        // ② 注销
        assertOk200(internalExchange(delete(INTERNAL_JOBS_URL + "/" + jobKey)), "注销应成功");
        // ③ 再注销（幂等收敛）
        assertOk200(internalExchange(delete(INTERNAL_JOBS_URL + "/" + jobKey)),
                "再注销应幂等成功");
    }
}
