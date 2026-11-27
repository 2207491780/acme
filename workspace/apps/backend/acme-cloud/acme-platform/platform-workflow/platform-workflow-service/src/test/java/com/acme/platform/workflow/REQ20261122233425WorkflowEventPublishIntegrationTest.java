package com.acme.platform.workflow;

import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.WorkflowIdentityClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261122-233425（BPM-5）流程事件流发布集成测试（契约确认④；AC-4 承载）。
 * Testcontainers Kafka + PG 双容器（framework-stream 装配口径：workflow-service 经
 * KafkaWorkflowEventPublisher 出站，topic=acme.workflow.events；信封=framework-stream
 * EventEnvelopes 口径）。装配基座照 {@code REQ20261121142316WorkflowEngineIntegrationTest}
 * 先例（Testcontainers PG + MockMvc + InternalTokenAuthFilter），Kafka 容器对齐
 * Modules.md 3.5「binder 由使用方引入」——binder brokers 由本测试 @DynamicPropertySource
 * 注入容器地址；无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证。
 *
 * <p>契约口径（contracts/REQ-20261122-233425.md 确认④）：既有 6 埋点 + 补 INSTANCE_STARTED
 * 全量出站；事件载荷 WorkflowEventEnvelope（eventType/instanceId/definitionId/definitionKey/
 * nodeId?/businessKey/tenantId/traceId/occurredAt）放 workflow-api；事件发布经
 * EventPublishInterceptor AFTER_COMMIT——回滚不发布（AC-4 反例腿）。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 本测试为<b>等实现</b>用例：依赖 backend 落地 workflow-service pom 增补
 *     （framework-stream + spring-cloud-stream-binder-kafka + testcontainers-kafka）与
 *     KafkaWorkflowEventPublisher 装配/绑定配置（destination=acme.workflow.events）；
 *     实现未落地前本类无法编译/运行，属预期（tester 交付口径如实标注，不伪造通过）；
 * A-2 Kafka 容器镜像=apache/kafka:3.9.0（TC 2.x org.testcontainers.kafka.KafkaContainer
 *     Apache Kafka 形态；confluent 形态由实现披露后可换，断言不依赖镜像方言）；
 * A-3 消费断言以原始 kafka-clients KafkaConsumer 轮询承载（spring-kafka 测试工具不入测试域，
 *     减少装配面）；记录 value=EventEnvelope JSON（MessageSender payload 口径，
 *     MessageSenderTest 先例）；
 * A-4 订阅时点=容器起后 @BeforeEach（auto.offset.reset=earliest + 随机 group），本类用例
 *     按 businessKey 唯一过滤互不污染；
 * A-5 回滚口径（实证修订 02:41 轮）：无路网关启动现为「启动成功+挂网关」合法形态（BPM-1
 *     A-8 前两态，A-8 先例第三形态「启动失败回滚」已不复现）——回滚腿改钉<b>命令校验失败</b>
 *     （非办理人 complete，BPM-1 AC-12 先例：命令失败无部分推进）：AFTER_COMMIT 拦截器语义下
 *     该命令不得出 INSTANCE_COMPLETED、实例保持 RUNNING；观察窗 8s 自旋为有限反例窗
 *     （pitfalls 15 口径：自旋+余量，不 sleep 死等）。</p>
 */
@SpringBootTest(classes = PlatformWorkflowApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=true",
        "spring.cloud.stream.kafka.binder.producer-properties.max.block.ms=15000",
        "acme.security.token.hmac-secret=req-233425-event-it-secret-01234567890"
})
class REQ20261122233425WorkflowEventPublishIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String DEFS_URL = "/admin/v1/workflow/process-definitions";
    private static final String INSTANCES_URL = "/admin/v1/workflow/process-instances";
    private static final String TASKS_URL = "/admin/v1/workflow/tasks";
    private static final String INTERNAL_URL = "/internal/v1/workflow/process-instances";

    /** 契约④钉死 topic（共享资源预占：通知中心 BPM-6 消费复用）。 */
    private static final String EVENTS_TOPIC = "acme.workflow.events";

    private static final MediaType JSON_TYPE = MediaType.APPLICATION_JSON;

    /** internal 调用方 sub=业务服务名（internal IT 同口径）。 */
    private static final String CALLER_SUB = "platform-leave";

    private static final String INITIATOR_USER_ID = "4301";

    /** 单任务直通（生命周期事件链夹具：start→task→complete→completed）。 */
    private static final String SINGLE_TASK_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-only"/>
            <userTask id="task-only" name="唯一任务" acme:assignee="4401"/>
            <sequenceFlow id="f1" sourceRef="task-only" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 零 Docker 进程内 KRaft Kafka broker（随机空闲端口，不抢占宿主 9092）。 */
    private static final EmbeddedKafkaKraftBroker KAFKA = startEmbeddedKafka();

    private static EmbeddedKafkaKraftBroker startEmbeddedKafka() {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, EVENTS_TOPIC);
        broker.afterPropertiesSet();
        return broker;
    }

    @AfterAll
    static void stopEmbeddedKafka() {
        KAFKA.destroy();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        registry.add("spring.datasource.url", () -> pg.jdbcUrl("acme_wf_events", "public"));
        registry.add("spring.datasource.username", pg::username);
        registry.add("spring.datasource.password", pg::password);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBrokersAsString);
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuthorizationClient authorizationClient;

    @MockitoBean
    private WorkflowIdentityClient workflowIdentityClient;

    private MockMvc mvc;

    /** 契约事件观测器（earliest + 随机 group；全类共享一次订阅，用例间按 businessKey 隔离）。 */
    private KafkaConsumer<String, String> observer;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
        when(authorizationClient.currentBuiltinRoles())
                .thenReturn(new ContractResult<>(true, "0", "ok", Set.of()));
        observer = eventObserver();
        observer.subscribe(List.of(EVENTS_TOPIC));
    }

    @AfterEach
    void cleanUp() {
        if (observer != null) {
            observer.close();
        }
        // teardown 清共享脏数据（叶子表先删防 FK；Kafka 消息随容器丢弃，断言按 businessKey 隔离）
        jdbcTemplate.update("delete from wf_timer_job");
        jdbcTemplate.update("delete from wf_history_activity");
        jdbcTemplate.update("delete from wf_variable");
        jdbcTemplate.update("delete from wf_task");
        jdbcTemplate.update("delete from wf_execution");
        jdbcTemplate.update("delete from wf_process_instance");
        jdbcTemplate.update("delete from wf_process_definition");
    }

    // ---------- AC-4：生命周期事件信封出站 ----------

    @Test
    void should_publish_lifecycle_event_envelopes_when_instance_started_task_created_and_completed()
            throws Exception {
        String key = uniq("evt-lifecycle");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String businessKey = uniq("evt-inst");

        // ① internal 启动（带初始变量）→ 引擎埋点 INSTANCE_STARTED + TASK_CREATED
        ObjectNode variables = bodyNode();
        variables.put("amount", 800);
        JsonNode started = internalCall(post(INTERNAL_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, businessKey, variables))));
        ok(started, "internal 启动应成功");
        String instanceId = instanceIdOf(started);

        // ② 完成任务 → 实例 COMPLETED → INSTANCE_COMPLETED
        Long taskId = awaitOpenTask(Long.parseLong(instanceId), "task-only", 10_000);
        assertThat(taskId).as("任务应创建（TASK_CREATED 埋点源）").isNotNull();
        JsonNode complete = completeAsAssignee("4401", taskId);
        ok(complete, "任务完成应成功");
        assertThat(await(10_000, () -> "COMPLETED".equals(instanceStatus(Long.parseLong(instanceId)))))
                .as("实例应执行到终点，实际=%s", instanceStatus(Long.parseLong(instanceId))).isTrue();

        // ③ Kafka 观测：topic=acme.workflow.events 收到三类事件信封（businessKey 过滤；
        //    三类齐即收口，超时返回累计命中）
        List<JsonNode> envelopes = collectUntil(30_000,
                envelope -> businessKey.equals(envelope.path("payload").path("businessKey").asText(null)),
                matched -> matched.stream()
                        .map(envelope -> envelope.path("payload").path("eventType").asText())
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(List.of("INSTANCE_STARTED", "TASK_CREATED", "INSTANCE_COMPLETED")));
        assertThat(envelopes).as("topic %s 应收到本 businessKey 的事件信封", EVENTS_TOPIC).isNotEmpty();

        List<String> eventTypes = envelopes.stream()
                .map(envelope -> envelope.path("payload").path("eventType").asText(null))
                .toList();
        assertThat(eventTypes).as("生命周期三类埋点应齐（确认④：INSTANCE_STARTED 补位）")
                .contains("INSTANCE_STARTED", "TASK_CREATED", "INSTANCE_COMPLETED");

        // ④ 信封形状断言（framework-stream EventEnvelopes 口径：messageId/traceId/idempotentKey/
        //    version + payload；载荷=WorkflowEventEnvelope 确认④字段域）
        for (JsonNode envelope : envelopes) {
            assertThat(envelope.path("messageId").asText())
                    .as("信封 messageId 非空：%s", envelope).isNotBlank();
            assertThat(envelope.path("traceId").asText())
                    .as("信封 traceId 非空：%s", envelope).isNotBlank();
            assertThat(envelope.path("idempotentKey").asText())
                    .as("信封 idempotentKey 非空（缺省=messageId）：%s", envelope).isNotBlank();
            assertThat(envelope.path("version").asText())
                    .as("信封 version 非空：%s", envelope).isNotBlank();
            JsonNode payload = envelope.path("payload");
            assertThat(payload.path("instanceId").asText())
                    .as("载荷 instanceId 回显：%s", payload).isEqualTo(instanceId);
            assertThat(payload.path("definitionId").asText())
                    .as("载荷 definitionId 回显：%s", payload).isEqualTo(String.valueOf(defId));
            assertThat(payload.path("definitionKey").asText())
                    .as("载荷 definitionKey 回显：%s", payload).isEqualTo(key);
            assertThat(payload.path("tenantId").asText())
                    .as("载荷 tenantId 回显：%s", payload).isEqualTo("default");
            assertThat(payload.has("traceId"))
                    .as("载荷 traceId 字段在场（确认④字段域；值不钉——实现披露载荷 "
                            + "traceId 缺省 null，链路对账走信封壳 traceId）")
                    .isTrue();
            assertThat(payload.path("occurredAt").asText())
                    .as("载荷 occurredAt 非空（type+occurredAt+payload 口径）：%s", payload).isNotBlank();
        }
        // INSTANCE_COMPLETED 载荷不应携带 nodeId（实例级事件）；TASK_CREATED 应携带节点 id
        assertThat(envelopes.stream()
                .filter(envelope -> "TASK_CREATED".equals(
                        envelope.path("payload").path("eventType").asText()))
                .allMatch(envelope -> "task-only".equals(
                        envelope.path("payload").path("nodeId").asText())))
                .as("TASK_CREATED 载荷应携带 nodeId（确认④ nodeId? 语义）").isTrue();
    }

    @Test
    void should_not_publish_event_when_command_rolls_back() throws Exception {
        String key = uniq("evt-rollback");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String businessKey = uniq("rollback-inst");

        // ① 启动成功提交（INSTANCE_STARTED 合法发布，A-8 允许形态：无路网关为启动成功+挂起
        //    ——本夹具用单任务直通，启动必成功提交）
        JsonNode started = internalCall(post(INTERNAL_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(internalStartBody(defId, businessKey,
                        bodyNode().put("amount", 5)))));
        ok(started, "internal 启动应成功提交");
        String instanceId = instanceIdOf(started);

        // ② 命令校验失败 → 整体回滚（BPM-1 AC-12 先例：complete 校验失败无部分推进）：
        //    非办理人 complete → 业务失败；AFTER_COMMIT 语义下该命令不得出事件
        Long taskId = awaitOpenTask(Long.parseLong(instanceId), "task-only", 10_000);
        assertThat(taskId).as("任务应创建").isNotNull();
        JsonNode failed = completeAsAssignee("4599", taskId);
        assertThat(failed.path("success").asBoolean())
                .as("非办理人 complete 应业务失败（整体回滚）：%s", failed).isFalse();
        assertThat(failed.path("code").asText())
                .as("业务失败 code≠0（pitfalls：勿断言 4xx）").isNotEqualTo("0");
        assertThat(instanceStatus(Long.parseLong(instanceId)))
                .as("回滚命令不得推进实例（无部分推进）").isEqualTo("RUNNING");

        // ③ 回滚命令不得出 INSTANCE_COMPLETED（观察窗 8s 全程反例自旋，A-5）
        List<JsonNode> leaked = collectFor(8_000,
                envelope -> businessKey.equals(envelope.path("payload").path("businessKey").asText(null))
                        && "INSTANCE_COMPLETED".equals(
                                envelope.path("payload").path("eventType").asText()));
        assertThat(leaked)
                .as("回滚命令不得发布事件（AFTER_COMMIT 语义）：%s", leaked).isEmpty();
    }

    // ---------- Kafka 观测支撑（A-3：原始 kafka-clients 轮询） ----------

    private KafkaConsumer<String, String> eventObserver() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "evt-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    /** 轮询整个窗口并返回全部命中记录（反例观察窗：存在性否定断言必须吃满窗口）。 */
    private List<JsonNode> collectFor(long windowMs, java.util.function.Predicate<JsonNode> hit)
            throws IOException {
        long deadline = System.currentTimeMillis() + windowMs;
        List<JsonNode> matched = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            matched.addAll(pollMatches(hit));
        }
        return matched;
    }

    /** 轮询至满足器命中（返回累计命中记录）；超时返回当前累计命中（可能空表）。 */
    private List<JsonNode> collectUntil(long timeoutMs, java.util.function.Predicate<JsonNode> hit,
                                        java.util.function.Predicate<List<JsonNode>> satisfied)
            throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<JsonNode> matched = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            matched.addAll(pollMatches(hit));
            if (satisfied.test(matched)) {
                return matched;
            }
        }
        return matched;
    }

    /** 单次 poll 并解析命中记录（topic 卫哨断言）。 */
    private List<JsonNode> pollMatches(java.util.function.Predicate<JsonNode> hit) throws IOException {
        List<JsonNode> matched = new ArrayList<>();
        ConsumerRecords<String, String> records = observer.poll(Duration.ofMillis(300));
        for (ConsumerRecord<String, String> record : records) {
            assertThat(record.topic()).as("观测应只订契约 topic").isEqualTo(EVENTS_TOPIC);
            JsonNode envelope = JSON.readTree(record.value());
            if (hit.test(envelope)) {
                matched.add(envelope);
            }
        }
        return matched;
    }

    // ---------- 流程驱动支撑（照抄先例口径） ----------

    private ObjectNode internalStartBody(long definitionId, String businessKey, ObjectNode variables) {
        ObjectNode body = bodyNode();
        body.put("definitionId", String.valueOf(definitionId));
        body.put("businessKey", businessKey);
        body.put("initiatorUserId", INITIATOR_USER_ID);
        if (variables != null) {
            body.set("variables", variables);
        }
        return body;
    }

    /** A-3：instanceId 宽容解析（data.instanceId / data.id / data 裸值）。 */
    private String instanceIdOf(JsonNode resp) {
        JsonNode data = resp.path("data");
        if (data.has("instanceId")) {
            return data.path("instanceId").asText();
        }
        if (data.has("id")) {
            return data.path("id").asText();
        }
        return data.isValueNode() ? data.asText() : data.toString();
    }

    private JsonNode internalCall(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                CALLER_SUB, "default", Channel.B, Set.of())))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "leave-svc", "1.0")))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("internal 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    /** 办理人完成（admin 面任务语义，token 即身份）。 */
    private JsonNode completeAsAssignee(String userId, long taskId) throws Exception {
        MvcResult result = mvc.perform(post(TASKS_URL + "/" + taskId + "/complete")
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                userId, "default", Channel.B, Set.of())))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0"))
                        .contentType(JSON_TYPE)
                        .content("{}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("完成不应 5xx：%s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    private long createAndDeploy(String key, String xml) throws Exception {
        ObjectNode body = bodyNode();
        body.put("key", key);
        body.put("name", key + "-name");
        body.put("bpmnXml", xml);
        ok(callAsSuperAdmin(post(DEFS_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body))), "创建草稿应成功");
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_definition where process_key = ? and deploy_version = 1",
                Long.class, key);
        assertThat(ids).as("定义 %s 应已落库", key).isNotEmpty();
        long id = ids.get(0);
        ok(callAsSuperAdmin(post(DEFS_URL + "/" + id + "/deploy")), "部署应成功");
        return id;
    }

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                "1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("admin 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    private static String bpmn(String processId, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:acme="http://acme.dev/bpmn/ext/v1"
                             targetNamespace="http://acme.dev/bpmn/it">
                  <process id="%s" name="%s">
                %s
                  </process>
                </definitions>
                """.formatted(processId, processId, body);
    }

    private String instanceStatus(long instanceId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select status from wf_process_instance where id = ?", String.class, instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 实例在指定节点的未终结任务（先例口径）。 */
    private Long findOpenTaskId(long instanceId, String nodeId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_task where instance_id = ? and node_id = ? "
                        + "and status in ('CREATED','CLAIMED') order by id desc",
                Long.class, instanceId, nodeId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long awaitOpenTask(long instanceId, String nodeId, long timeoutMs) {
        await(timeoutMs, () -> findOpenTaskId(instanceId, nodeId) != null);
        return findOpenTaskId(instanceId, nodeId);
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

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    private String uniq(String suffix) {
        return "wf233425evt" + SEQ.incrementAndGet() + suffix;
    }

    private ObjectNode bodyNode() {
        return JSON.createObjectNode();
    }
}
