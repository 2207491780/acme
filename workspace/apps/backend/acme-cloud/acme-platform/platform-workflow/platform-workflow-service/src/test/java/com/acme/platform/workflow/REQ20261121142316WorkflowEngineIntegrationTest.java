package com.acme.platform.workflow;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.WorkflowIdentityClient;
import com.acme.platform.workflow.api.WorkflowErrorCodes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261121-142316 BPM 引擎内核 MVP——服务级集成测试（Testcontainers PG 自起容器，
 * 无 Kafka——本 REQ 仅 EventPublish SPI 钩子+logging no-op；装配口径照
 * {@code REQ20261121071002SchedulerCoreIntegrationTest} 先例：MockMvc HTTP 面 +
 * JdbcTemplate 夹具；ServiceTask 委托与调度中心注册/注销用内嵌 JDK HttpServer 桩
 * （JOB-1 先例）；无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>AC 覆盖映射（测试名→契约验收标准，TL 回填用）：</p>
 * <ul>
 *   <li><b>AC-1</b>：{@link #should_prepare_seven_tables_and_unique_indexes_when_v1_migrated}
 *       （workflow V1 七表 + uk 索引 + Flyway 成功；V27 权限 78~82 种子/幂等/坑 16 水位校准由
 *       platform-system 侧 SystemIntegrationTest 等四处适配承载，映射见交付报告）。</li>
 *   <li><b>AC-2</b>：部署生命周期正向 {@link #should_manage_definition_lifecycle_when_draft_saved_deployed_and_disabled}
 *       + 全元素正向 {@link #should_deploy_and_execute_kitchen_sink_process_when_all_phase1_elements_valid}
 *       （每条校验规则的正例均由二者承载）+ 九条规则反例（#4~#12 系列逐规则）+ 重复 key
 *       {@link #should_reject_duplicate_definition_key_when_creating_draft}。</li>
 *   <li><b>AC-3</b>：排他网关 {@link #should_route_first_matching_condition_when_exclusive_gateway_evaluates}
 *       / default 兜底 {@link #should_fall_back_to_default_flow_when_no_condition_matches}
 *       / 无条件异常挂起 {@link #should_park_instance_without_task_when_gateway_has_no_route}。</li>
 *   <li><b>AC-4</b>：并行 fork-join {@link #should_aggregate_join_and_continue_when_all_parallel_branches_complete}
 *       （含部分到达不推进腿）。</li>
 *   <li><b>AC-5</b>：指派各型 {@link #should_resolve_assignee_and_initiator_rules_when_user_task_created} /
 *       {@link #should_offer_candidate_task_and_claim_when_candidate_matches}（roles+users+claim 生效+done）
 *       / 无候选人挂起 {@link #should_suspend_instance_when_no_assignee_resolved}
 *       / candidateDepts {@link #should_resolve_department_candidates_when_candidate_depts_rule_matches}（A-7 宽容）
 *       / 租户隔离 {@link #should_isolate_todo_by_tenant_when_multi_tenant_instances_exist}。</li>
 *   <li><b>AC-6</b>：状态机矩阵——complete 正 {@link #should_complete_task_and_advance_when_assignee_submits_form}；
 *       reject 三型 {@link #should_reject_to_previous_user_task_when_reject_without_target} /
 *       {@link #should_reject_to_specified_historical_node_when_target_node_id_given} /
 *       {@link #should_withdraw_running_instance_when_initiator_terminates}；
 *       非法跃迁 {@link #should_reject_complete_when_operator_is_not_assignee}（ASSIGNEE_MISMATCH）/
 *       {@link #should_reject_unclaim_and_double_claim_when_task_state_invalid}（STATE_CONFLICT）/
 *       {@link #should_transfer_task_to_new_assignee_when_transfer_requested} /
 *       {@link #should_delegate_once_and_refuse_second_level_when_delegate_chain_exceeded}（DELEGATE_STATE_INVALID）。</li>
 *   <li><b>AC-7</b>：{@link #should_converge_single_winner_when_two_threads_claim_concurrently} +
 *       {@link #should_converge_single_winner_when_two_threads_complete_concurrently}（两线程竞争收敛单胜）
 *       + 乐观锁连续冲突 {@link #should_converge_or_rollback_cleanly_when_optimistic_lock_version_conflicts}。</li>
 *   <li><b>AC-8</b>：{@link #should_dispatch_delegation_and_advance_when_service_callback_succeeds}（下发协议体断言）
 *       + {@link #should_suspend_instance_when_service_task_callbacks_fail_until_retry_exhausted}（重试耗尽挂起）。</li>
 *   <li><b>AC-9</b>：{@link #should_fire_boundary_timer_and_interrupt_task_when_scheduler_fires}
 *       （注册协议 jobKey=wf-timer:* + fire 幂等 + 重复 fire 拒绝）
 *       + {@link #should_cancel_timer_and_deregister_when_task_completes_before_due}（先完成注销+注销后 fire 拒绝）。</li>
 *   <li><b>AC-10</b>：{@link #should_shadow_variables_and_recycle_subprocess_scope_when_subprocess_completes}
 *       （读穿透 + 写不穿透 + 独立 scope + 子树回收）。</li>
 *   <li><b>AC-11</b>：{@link #should_suspend_and_resume_instance_when_admin_operates}
 *       + {@link #should_cancel_all_unfinished_and_terminate_when_instance_terminated}（状态机矩阵）。</li>
 *   <li><b>AC-12</b>：{@link #should_rollback_without_partial_advance_when_command_validation_fails}
 *       + {@link #should_converge_or_rollback_cleanly_when_optimistic_lock_version_conflicts}。</li>
 *   <li><b>AC-13</b>：{@link #should_invalidate_compiled_model_and_serve_new_version_when_disabled_or_redeployed}。</li>
 *   <li><b>AC-14</b>：{@link #should_route_workflow_surface_and_doc_group_without_internal_exposure}（网关源级）
 *       + {@link #should_aggregate_workflow_module_into_bootstrap}（bootstrap pom/主类扫描面）。</li>
 *   <li><b>AC-15</b>：{@link #should_record_ordered_history_chain_with_reject_edge_when_task_rejected}
 *       + {@link #should_record_timer_fire_history_when_boundary_timer_fired}（ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE 按序）。</li>
 * </ul>
 *
 * <p>业务失败一律 Result 信封 code≠0；wire 码断言引用 platform-workflow-api 的
 * {@link WorkflowErrorCodes} 常量（api 契约钉死，scheduler 先例同款），不猜字面量段位。
 * internal 面（fire/callback）照 REQ-054613/Scheduler 先例：InternalTokenAuthFilter 显式装配 +
 * X-Acme-Token 内部令牌（tenant=default 与夹具实例租户对齐）。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 主应用类=com.acme.platform.workflow.PlatformWorkflowApplication（双模块+惯例推导，已实证在场）；
 * A-2 调度中心注册/注销与 ServiceTask 委托目标经 Spring Cloud LoadBalancer lb://{appName} 解析
 *     （spec §3.6 默认「Nacos 服务名」解析器），测试以 SimpleDiscoveryClient 静态实例表把
 *     platform-scheduler 与 acme-service-stub 指到内嵌桩（SchedulerCore 先例 A-2 同款）——
 *     Nacos 关闭态下 lb:// 唯一可达解析面；
 * A-3 请求/响应体形状按 platform-workflow-api DTO 字段名（key/name/bpmnXml/definitionId/
 *     businessKey/variables/form/targetNodeId/comment/assignee/success/result/error/reason）；
 *     分页 data.records 宽容解析；Long wire=字符串（FR-024），断言经 Jackson 数值宽容读取；
 * A-4 BPMN 条件表达式用 BPMN 标准 ${...} 包装（xsi:type=tFormalExpression），内部为契约钉死的
 *     SpEL（var()/hasRole()/initiator 常量）；定时器定义用标准 timeDuration（ISO-8601）；
 * A-5 ServiceTask 重试配置挂 acme:retryMaxAttempts/acme:retryInitialIntervalMs（沿用 scheduler
 *     注册体字段名先例；设计 §2 仅钉 acme:retryPolicy 概念名）；
 * A-6 同 key 版本自增的可达路径二选一宽容：DEPLOYED 定义直接再 deploy（version+1）或
 *     PUT 新 XML 后再 deploy（创建草稿撞 key 已被 KEY_DUPLICATE 拒绝，version+1 只能经此二路）；
 * A-7 候选/hasRole 数据源=契约 WorkflowIdentityClient（system-api，Long userId 签名）：测试
 *     用户 id 全量数字化并以 MockitoBean 桩定四端点（未知 id/编码/部门空集 fail-closed 与生产
 *     一致）——candidateDepts 正例转确定性断言；解析空形态由 no-assignee 用例（roleUserIds
 *     空集）承载；
 * A-8 网关无路（no-route）挂起形态宽容三态：start 业务失败但实例承载入库（对齐 03-0005
 *     「失败+实例承载」先例）/ start 成功+execution 停网关（「挂起该 execution」字面）/
 *     start 业务失败+命令整体回滚（确认③口径，实证形态）——前两态共同不变量=实例存在、
 *     无任务产生、不终态；第三态=无部分推进。与 spec §2 的张力归 TL 裁决；
 * A-9 ServiceTask 重试=重新下发（ServiceTaskDispatchPayload.attempt 字段承载尝试序号），
 *     每次下发均以失败回调应答直至耗尽；若实现为「回调失败即计数不重发」则重试证据断言
 *     （下发次数≥2）失败并如实上报；
 * A-10 夹具形态对齐实证校验器口径：①「start/end 恰一」按 end==1 执行（spec §5 校验规则字面），
 *     timer/boundary 双分支汇入同一 end 节点（BPMN 多流入单 end 合法）；②default 流采用 BPMN
 *     标准网关属性 default="flowId" 标注——实证实现校验器当前忽略该属性（把无条件流按
 *     GATEWAY_CONDITIONS 违规上报，AC-3 default 用例红），属实现偏差候选，TL 裁决后回改规格或实现。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformWorkflowApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-workflow-it-secret-0123456789"
})
class REQ20261121142316WorkflowEngineIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** ServiceTask 委托目标端点应用名（A-2：SimpleDiscoveryClient 静态实例表指向内嵌桩）。 */
    private static final String SERVICE_APP = "acme-service-stub";

    private static final String DEFS_URL = "/admin/v1/workflow/process-definitions";
    private static final String INSTANCES_URL = "/admin/v1/workflow/process-instances";
    private static final String TASKS_URL = "/admin/v1/workflow/tasks";
    private static final String TIMER_FIRE_URL = "/internal/v1/workflow/timers/%s/fire";
    private static final String SVC_CALLBACK_URL = "/internal/v1/workflow/service-tasks/%s/callback";
    private static final String SCHEDULER_REGISTER_PATH = "/internal/v1/scheduler/jobs";
    private static final String DELEGATE_PATH = "/internal/business/delegate";

    private static final MediaType JSON_TYPE = MediaType.APPLICATION_JSON;

    /** 内嵌 ServiceTask 目标桩（记录委托 POST；JDK HttpServer 照 JOB-1 先例）。 */
    private static final ServiceStub SERVICE_STUB = ServiceStub.start();

    /** 内嵌调度中心桩（记录一次性任务注册 POST 与注销 DELETE，按契约 §6 协议应答）。 */
    private static final SchedulerStub SCHEDULER_STUB = SchedulerStub.start();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        // A-2：Nacos 关闭态以 SimpleDiscoveryClient 静态实例表承载 lb://{appName}
        registry.add("spring.cloud.discovery.client.simple.instances.platform-scheduler[0].uri",
                () -> SCHEDULER_STUB.baseUri());
        registry.add("spring.cloud.discovery.client.simple.instances." + SERVICE_APP + "[0].uri",
                () -> SERVICE_STUB.baseUri());
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跨域权限判定桩（SchedulerCore 先例：判定走 platform-system Feign 契约，单服务测试域
     * 以 MockitoBean 桩件承载——缺省放行）。 */
    @MockitoBean
    private AuthorizationClient authorizationClient;

    /** 跨域身份目录桩（REQ-20261121-142316 契约 WorkflowIdentityClient：候选解析与
     * hasRole/inDept 数据源；数字 userId 语义对齐 api Long 签名——测试用户 id 全量数字化）。 */
    @MockitoBean
    private WorkflowIdentityClient workflowIdentityClient;

    private MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 身份目录夹具：userId→角色码（hasRole 内置函数/候选可见/todo 匹配数据源）。 */
    private static final Map<Long, Set<String>> USER_ROLE_CODES = Map.of(
            1101L, Set.of("wf-role-a"),
            2101L, Set.of("super_admin"),
            2201L, Set.of("race-qa"),
            2202L, Set.of("race-qa"));

    /** 身份目录夹具：角色码→启用用户集（候选解析/NO_ASSIGNEE_RESOLVED 判定数据源）。 */
    private static final Map<String, Set<Long>> ROLE_USER_IDS = Map.of(
            "wf-role-a", Set.of(1101L),
            "super_admin", Set.of(2101L),
            "race-qa", Set.of(2201L, 2202L));

    /** 身份目录夹具：userId→主属部门 id（candidateDepts 匹配）。 */
    private static final Map<Long, Long> USER_DEPT_IDS = Map.of(2401L, 10L, 2402L, 10L);

    /** 身份目录夹具：部门 id→用户集（candidateDepts 解析）。 */
    private static final Map<Long, Set<Long>> DEPT_USER_IDS = Map.of(10L, Set.of(2401L, 2402L));

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        SERVICE_STUB.reset();
        SCHEDULER_STUB.reset();
        when(authorizationClient.hasPermission(anyString()))
                .thenReturn(new ContractResult<>(true, "0", "ok", true));
        // 内置角色实查同桩（空集=无内置角色）
        when(authorizationClient.currentBuiltinRoles())
                .thenReturn(new ContractResult<>(true, "0", "ok", Set.of()));
        // 身份目录四端点契约桩（未知 id/编码/部门一律空集，fail-closed 语义与生产一致）
        when(workflowIdentityClient.userRoleCodes(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> new ContractResult<>(true, "0", "ok",
                        USER_ROLE_CODES.getOrDefault(inv.getArgument(0, Long.class), Set.of())));
        when(workflowIdentityClient.userDeptId(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> new ContractResult<>(true, "0", "ok",
                        USER_DEPT_IDS.get(inv.getArgument(0, Long.class))));
        when(workflowIdentityClient.roleUserIds(anyString()))
                .thenAnswer(inv -> new ContractResult<>(true, "0", "ok",
                        ROLE_USER_IDS.getOrDefault(inv.getArgument(0, String.class), Set.of())));
        when(workflowIdentityClient.deptUserIds(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> new ContractResult<>(true, "0", "ok",
                        DEPT_USER_IDS.getOrDefault(inv.getArgument(0, Long.class), Set.of())));
    }

    @AfterEach
    void cleanUp() {
        // teardown 清共享脏数据（每测试类独立 PG 容器，本类用例是唯一写入方；叶子表先删防 FK）。
        // 已到期本地兜底 timer 若在清库后触发，仅产生空转日志不落行（幂等 CAS PENDING 不命中）。
        SERVICE_STUB.reset();
        SCHEDULER_STUB.reset();
        jdbcTemplate.update("delete from wf_timer_job");
        jdbcTemplate.update("delete from wf_history_activity");
        jdbcTemplate.update("delete from wf_variable");
        jdbcTemplate.update("delete from wf_task");
        jdbcTemplate.update("delete from wf_execution");
        jdbcTemplate.update("delete from wf_process_instance");
        jdbcTemplate.update("delete from wf_process_definition");
    }

    // ---------- BPMN XML 夹具（标准 namespace + acme: 扩展；一期全元素组合） ----------

    /** 标准 definitions 包装（A-4：条件表达式 ${...} 内为 SpEL）。 */
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

    /** 排他网关全分支（AC-3）：两条件流 + default 流；task-mid 用 ${initiator} 指派。 */
    private static final String XOR_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>
            <exclusiveGateway id="gw" name="金额网关" default="gw-small"/>
            <sequenceFlow id="gw-big" sourceRef="gw" targetRef="task-big">
              <conditionExpression xsi:type="tFormalExpression">${var('amount') &gt;= 1000}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="gw-mid" sourceRef="gw" targetRef="task-mid">
              <conditionExpression xsi:type="tFormalExpression">${var('amount') &gt;= 100}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="gw-small" sourceRef="gw" targetRef="task-small"/>
            <userTask id="task-big" name="大额审批" acme:assignee="1001"/>
            <userTask id="task-mid" name="中额审批" acme:assignee="${initiator}"/>
            <userTask id="task-small" name="小额审批" acme:candidateRoles="wf-role-a"/>
            <sequenceFlow id="fb" sourceRef="task-big" targetRef="end"/>
            <sequenceFlow id="fm" sourceRef="task-mid" targetRef="end"/>
            <sequenceFlow id="fs" sourceRef="task-small" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 排他网关无 default（AC-3 无条件异常挂起腿）：两条件流、无 default。 */
    private static final String XOR_NO_DEFAULT_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>
            <exclusiveGateway id="gw"/>
            <sequenceFlow id="g1" sourceRef="gw" targetRef="task-x">
              <conditionExpression xsi:type="tFormalExpression">${var('amount') &gt;= 1000}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="g2" sourceRef="gw" targetRef="task-y">
              <conditionExpression xsi:type="tFormalExpression">${var('amount') &gt;= 100}</conditionExpression>
            </sequenceFlow>
            <userTask id="task-x" acme:assignee="2951"/>
            <userTask id="task-y" acme:assignee="2952"/>
            <sequenceFlow id="fx" sourceRef="task-x" targetRef="end"/>
            <sequenceFlow id="fy" sourceRef="task-y" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 并行 fork-join（AC-4）：fork 3 支 → join → 汇合后任务。 */
    private static final String PARALLEL_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="fork"/>
            <parallelGateway id="fork"/>
            <sequenceFlow id="pa" sourceRef="fork" targetRef="task-a"/>
            <sequenceFlow id="pb" sourceRef="fork" targetRef="task-b"/>
            <sequenceFlow id="pc" sourceRef="fork" targetRef="task-c"/>
            <userTask id="task-a" name="分支A" acme:assignee="2801"/>
            <userTask id="task-b" name="分支B" acme:assignee="2801"/>
            <userTask id="task-c" name="分支C" acme:assignee="2801"/>
            <sequenceFlow id="ja" sourceRef="task-a" targetRef="join"/>
            <sequenceFlow id="jb" sourceRef="task-b" targetRef="join"/>
            <sequenceFlow id="jc" sourceRef="task-c" targetRef="join"/>
            <parallelGateway id="join"/>
            <sequenceFlow id="jo" sourceRef="join" targetRef="task-after"/>
            <userTask id="task-after" name="汇合后" acme:assignee="2801"/>
            <sequenceFlow id="f9" sourceRef="task-after" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 驳回链两任务（AC-6 缺省回上一）：start → t1 → t2 → end。 */
    private static final String REJECT_CHAIN_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="t1"/>
            <userTask id="t1" name="一级审批" acme:assignee="2601"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="t2"/>
            <userTask id="t2" name="二级审批" acme:assignee="2602"/>
            <sequenceFlow id="f2" sourceRef="t2" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 驳回链三任务（AC-6 指定回退腿）：start → t1 → t2 → t3 → end。 */
    private static final String REJECT_CHAIN3_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="t1"/>
            <userTask id="t1" name="一级" acme:assignee="2701"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="t2"/>
            <userTask id="t2" name="二级" acme:assignee="2702"/>
            <sequenceFlow id="f2" sourceRef="t2" targetRef="t3"/>
            <userTask id="t3" name="三级" acme:assignee="2703"/>
            <sequenceFlow id="f3" sourceRef="t3" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 单任务直通（并发/挂起恢复/回滚等基础夹具）。 */
    private static final String SINGLE_TASK_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-only"/>
            <userTask id="task-only" name="唯一任务" acme:assignee="2501"/>
            <sequenceFlow id="f1" sourceRef="task-only" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 候选任务（AC-7 claim 竞争/AC-5 候选各型），候选面由 %s 注入。 */
    private static final String CANDIDATE_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-cand"/>
            <userTask id="task-cand" name="候选任务" acme:candidateRoles="%s"/>
            <sequenceFlow id="f1" sourceRef="task-cand" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** candidateUsers 型候选任务（AC-5）。 */
    private static final String CANDIDATE_USERS_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-cand"/>
            <userTask id="task-cand" name="候选任务" acme:candidateUsers="%s"/>
            <sequenceFlow id="f1" sourceRef="task-cand" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** candidateDepts 型候选任务（AC-5；候选面=部门 id 列表）。 */
    private static final String CANDIDATE_DEPTS_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-cand"/>
            <userTask id="task-cand" name="候选任务" acme:candidateDepts="%s"/>
            <sequenceFlow id="f1" sourceRef="task-cand" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 子流程（AC-10）：入口网关验读穿透 → 子任务（表单写变量）→ 二级网关验遮蔽 → 尾任务。 */
    private static final String SUBPROCESS_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="sub"/>
            <subProcess id="sub" name="子流程">
              <startEvent id="sub-start"/>
              <sequenceFlow id="sf0" sourceRef="sub-start" targetRef="sub-gw1"/>
              <exclusiveGateway id="sub-gw1"/>
              <sequenceFlow id="sg-low" sourceRef="sub-gw1" targetRef="sub-low">
                <conditionExpression xsi:type="tFormalExpression">${var('amount') &lt; 10}</conditionExpression>
              </sequenceFlow>
              <sequenceFlow id="sg-high" sourceRef="sub-gw1" targetRef="sub-high">
                <conditionExpression xsi:type="tFormalExpression">${var('amount') &gt;= 10}</conditionExpression>
              </sequenceFlow>
              <userTask id="sub-low" name="子-低" acme:assignee="3101"/>
              <userTask id="sub-high" name="子-高" acme:assignee="3101"/>
              <sequenceFlow id="sl" sourceRef="sub-low" targetRef="sub-gw2"/>
              <sequenceFlow id="sh" sourceRef="sub-high" targetRef="sub-gw2"/>
              <exclusiveGateway id="sub-gw2"/>
              <sequenceFlow id="sg-big" sourceRef="sub-gw2" targetRef="sub-big">
                <conditionExpression xsi:type="tFormalExpression">${var('amount') &gt;= 50}</conditionExpression>
              </sequenceFlow>
              <sequenceFlow id="sg-small" sourceRef="sub-gw2" targetRef="sub-small">
                <conditionExpression xsi:type="tFormalExpression">${var('amount') &lt; 50}</conditionExpression>
              </sequenceFlow>
              <userTask id="sub-big" name="子-大" acme:assignee="3101"/>
              <userTask id="sub-small" name="子-小" acme:assignee="3101"/>
              <sequenceFlow id="sb1" sourceRef="sub-big" targetRef="sub-end"/>
              <sequenceFlow id="sb2" sourceRef="sub-small" targetRef="sub-end"/>
              <endEvent id="sub-end"/>
            </subProcess>
            <sequenceFlow id="f1" sourceRef="sub" targetRef="tail"/>
            <userTask id="tail" name="尾任务" acme:assignee="3102"/>
            <sequenceFlow id="f2" sourceRef="tail" targetRef="end"/>
            <endEvent id="end"/>
            """;

    /** 定时边界事件（AC-9）：中断型，挂 task-main；边界分支 task-boundary（双分支汇入同一
     * 终态节点——校验规则「start/end 恰一」按 end==1 执行，BPMN 多流合入单 end 合法）。 */
    private static final String TIMER_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="task-main"/>
            <userTask id="task-main" name="限时办理" acme:assignee="2901"/>
            <sequenceFlow id="f1" sourceRef="task-main" targetRef="end"/>
            <endEvent id="end"/>
            <boundaryEvent id="timer-b" attachedToRef="task-main" cancelActivity="true">
              <timerEventDefinition>
                <timeDuration>PT5M</timeDuration>
              </timerEventDefinition>
            </boundaryEvent>
            <sequenceFlow id="tb" sourceRef="timer-b" targetRef="task-boundary"/>
            <userTask id="task-boundary" name="超时处理" acme:assignee="2902"/>
            <sequenceFlow id="f2" sourceRef="task-boundary" targetRef="end"/>
            """;

    /** ServiceTask 委托（AC-8）：HTTP 委托到内嵌桩 + 重试配置（A-5：属性名对齐主 yml
     * acme.workflow.delegate 注释锚 acme:timeoutMs/acme:retryMaxAttempts/acme:retryBackoffMs）。 */
    private static final String SERVICE_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="svc"/>
            <serviceTask id="svc" name="外部委托"
                         acme:serviceRef="%s"
                         acme:callbackPath="%s"
                         acme:timeoutMs="5000"
                         acme:retryMaxAttempts="2"
                         acme:retryBackoffMs="100"/>
            <sequenceFlow id="f1" sourceRef="svc" targetRef="task-after"/>
            <userTask id="task-after" name="委托后" acme:assignee="3001"/>
            <sequenceFlow id="f2" sourceRef="task-after" targetRef="end"/>
            <endEvent id="end"/>
            """.formatted(SERVICE_APP, DELEGATE_PATH);

    /** 全元素组合正向夹具（AC-2 正例之锚）：fork/join + 候选 + 委托 + 子流程 + 中断边界 + hasRole 网关。 */
    private static final String KITCHEN_SINK_BODY = """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="fork"/>
            <parallelGateway id="fork"/>
            <sequenceFlow id="p1" sourceRef="fork" targetRef="task-cand"/>
            <sequenceFlow id="p2" sourceRef="fork" targetRef="svc"/>
            <userTask id="task-cand" name="候选任务" acme:candidateRoles="super_admin"/>
            <serviceTask id="svc" name="委托"
                         acme:serviceRef="%s"
                         acme:callbackPath="%s"/>
            <sequenceFlow id="j1" sourceRef="task-cand" targetRef="join"/>
            <sequenceFlow id="j2" sourceRef="svc" targetRef="join"/>
            <parallelGateway id="join"/>
            <sequenceFlow id="jo" sourceRef="join" targetRef="sub"/>
            <subProcess id="sub" name="子流程">
              <startEvent id="sub-start"/>
              <sequenceFlow id="sf0" sourceRef="sub-start" targetRef="sub-task"/>
              <userTask id="sub-task" name="子任务" acme:assignee="${initiator}"/>
              <sequenceFlow id="sf1" sourceRef="sub-task" targetRef="sub-end"/>
              <endEvent id="sub-end"/>
            </subProcess>
            <boundaryEvent id="timer-b" attachedToRef="sub" cancelActivity="true">
              <timerEventDefinition>
                <timeDuration>PT30M</timeDuration>
              </timerEventDefinition>
            </boundaryEvent>
            <sequenceFlow id="tb" sourceRef="timer-b" targetRef="boundary-task"/>
            <userTask id="boundary-task" name="边界任务" acme:assignee="2902"/>
            <sequenceFlow id="f4" sourceRef="boundary-task" targetRef="end"/>
            <sequenceFlow id="f1" sourceRef="sub" targetRef="gw2"/>
            <exclusiveGateway id="gw2" default="g2b"/>
            <sequenceFlow id="g2a" sourceRef="gw2" targetRef="task-tail-admin">
              <conditionExpression xsi:type="tFormalExpression">${hasRole('super_admin')}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="g2b" sourceRef="gw2" targetRef="task-tail-user"/>
            <userTask id="task-tail-admin" name="管理员尾任务" acme:assignee="2102"/>
            <userTask id="task-tail-user" name="普通尾任务" acme:assignee="2103"/>
            <sequenceFlow id="f2" sourceRef="task-tail-admin" targetRef="end"/>
            <sequenceFlow id="f3" sourceRef="task-tail-user" targetRef="end"/>
            <endEvent id="end"/>
            """.formatted(SERVICE_APP, DELEGATE_PATH);

    // ---------- 反例夹具（AC-2 部署校验逐规则） ----------

    private static final String INVALID_NO_START = bpmn("proc-no-start", """
            <userTask id="t1" acme:assignee="u-a"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_DANGLING_FLOW = bpmn("proc-dangling", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="ghost-node"/>
            <userTask id="t1" acme:assignee="u-a"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_GATEWAY_UNCONDITIONAL = bpmn("proc-gw-nocond", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>
            <exclusiveGateway id="gw"/>
            <sequenceFlow id="g1" sourceRef="gw" targetRef="t1"/>
            <sequenceFlow id="g2" sourceRef="gw" targetRef="t2"/>
            <userTask id="t1" acme:assignee="u-a"/>
            <userTask id="t2" acme:assignee="u-b"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <sequenceFlow id="f2" sourceRef="t2" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_DUPLICATE_IDS = bpmn("proc-dup-id", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="dup"/>
            <userTask id="dup" name="其一" acme:assignee="u-a"/>
            <userTask id="dup" name="其二" acme:assignee="u-b"/>
            <sequenceFlow id="f1" sourceRef="dup" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_SPEL_SYNTAX = bpmn("proc-spel-bad", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>
            <exclusiveGateway id="gw" default="g2"/>
            <sequenceFlow id="g1" sourceRef="gw" targetRef="t1">
              <conditionExpression xsi:type="tFormalExpression">${var('amount' &gt;}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="g2" sourceRef="gw" targetRef="t2"/>
            <userTask id="t1" acme:assignee="u-a"/>
            <userTask id="t2" acme:assignee="u-b"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <sequenceFlow id="f2" sourceRef="t2" targetRef="end"/>
            <endEvent id="end"/>
            """);

    /** SpEL 安全红线反例：类型引用 T(...)（SimpleEvaluationContext 禁类型引用/Bean 访问）。 */
    private static final String INVALID_SPEL_TYPE_REFERENCE = bpmn("proc-spel-type", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>
            <exclusiveGateway id="gw" default="g2"/>
            <sequenceFlow id="g1" sourceRef="gw" targetRef="t1">
              <conditionExpression xsi:type="tFormalExpression">${T(java.lang.Runtime).getRuntime()}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="g2" sourceRef="gw" targetRef="t2"/>
            <userTask id="t1" acme:assignee="u-a"/>
            <userTask id="t2" acme:assignee="u-b"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <sequenceFlow id="f2" sourceRef="t2" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_USER_TASK_NO_RULE = bpmn("proc-task-norule", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="t1"/>
            <userTask id="t1" name="无指派"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_SERVICE_NO_REF = bpmn("proc-svc-noref", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="svc"/>
            <serviceTask id="svc" acme:callbackPath="/x"/>
            <sequenceFlow id="f1" sourceRef="svc" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_SERVICE_NO_PATH = bpmn("proc-svc-nopath", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="svc"/>
            <serviceTask id="svc" acme:serviceRef="some-service"/>
            <sequenceFlow id="f1" sourceRef="svc" targetRef="end"/>
            <endEvent id="end"/>
            """);

    private static final String INVALID_BOUNDARY_UNATTACHED = bpmn("proc-boundary-free", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="t1"/>
            <userTask id="t1" acme:assignee="u-a"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <endEvent id="end"/>
            <boundaryEvent id="timer-free" cancelActivity="true">
              <timerEventDefinition>
                <timeDuration>PT1M</timeDuration>
              </timerEventDefinition>
            </boundaryEvent>
            """);

    private static final String INVALID_NO_END = bpmn("proc-no-end", """
            <startEvent id="start"/>
            <sequenceFlow id="f0" sourceRef="start" targetRef="t1"/>
            <userTask id="t1" acme:assignee="u-a"/>
            """);

    private static final String INVALID_DOUBLE_START = bpmn("proc-two-start", """
            <startEvent id="start-1"/>
            <startEvent id="start-2"/>
            <sequenceFlow id="f0" sourceRef="start-1" targetRef="t1"/>
            <userTask id="t1" acme:assignee="u-a"/>
            <sequenceFlow id="f1" sourceRef="t1" targetRef="end"/>
            <endEvent id="end"/>
            """);

    // ---------- HTTP 支撑 ----------

    private String userToken(String userId, String tenant, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, tenant, Channel.B, roles));
    }

    private MvcResult performAs(String userId, String tenant, Set<String> roles,
                                MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", userToken(userId, tenant, roles))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    /** 平台管理操作者（种子超管 userId=1 / default 租户 / super_admin 短路全量权限点）。 */
    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = performAs("1", "default", Set.of("super_admin"), request);
        assertThat(result.getResponse().getStatus())
                .as("admin 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    /** internal 面（MockMvc 直调 + 内部令牌；tenant=default 与夹具实例租户对齐）。 */
    private JsonNode internalCall(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                "wf-internal-it", "default", Channel.B, Set.of())))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "it-stub", "1.0")))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("internal 面不应 5xx：%s -> %s", request,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + "：%s", body).isTrue();
        assertThat(body.path("code").asText()).as(desc + " code 应为 0：%s", body).isEqualTo("0");
        return body;
    }

    /** 业务失败信封断言（pitfalls 口径：Result.code≠0，勿断言 4xx）。 */
    private JsonNode bizFail(JsonNode body, String desc) {
        assertThat(body.path("success").asBoolean()).as(desc + " 应业务失败：%s", body).isFalse();
        assertThat(body.path("code").asText()).as(desc + " code 应≠0：%s", body).isNotEqualTo("0");
        return body;
    }

    private JsonNode bizFailCode(JsonNode body, String expectedCode, String desc) {
        bizFail(body, desc);
        assertThat(body.path("code").asText()).as(desc + " wire 码").isEqualTo(expectedCode);
        return body;
    }

    /** 分页/列表 data 形状宽容解析：data.records 优先，退化 data 数组（A-3）。 */
    private List<JsonNode> dataItems(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode items = data.has("records") ? data.path("records") : data;
        List<JsonNode> list = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(list::add);
        }
        return list;
    }

    private String uniq(String suffix) {
        return "wf142316" + SEQ.incrementAndGet() + suffix;
    }

    private ObjectNode bodyNode() {
        return JSON.createObjectNode();
    }

    /** 启动变量（数值/字符串双型）。 */
    private ObjectNode vars(String key, Object value) {
        ObjectNode node = bodyNode();
        if (value instanceof Number number) {
            node.put(key, number.longValue());
        } else {
            node.put(key, String.valueOf(value));
        }
        return node;
    }

    // ---------- 定义域夹具支撑 ----------

    /** 创建草稿（可携 XML）；id 经 DB 回查（不猜响应 data 形状）。 */
    private long createDraft(String key, String name, String xml) throws Exception {
        ObjectNode body = bodyNode();
        body.put("key", key);
        body.put("name", name);
        if (xml != null) {
            body.put("bpmnXml", xml);
        }
        ok(callAsSuperAdmin(post(DEFS_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body))), "创建草稿应成功");
        return definitionIdOf(key, 1);
    }

    /** 保存 XML（PUT /{id}/xml）。 */
    private void saveXml(long definitionId, String xml) throws Exception {
        ObjectNode body = bodyNode();
        body.put("bpmnXml", xml);
        ok(callAsSuperAdmin(put(DEFS_URL + "/" + definitionId + "/xml")
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body))), "保存 XML 应成功");
    }

    /** 部署（POST /{id}/deploy，成功信封）。 */
    private void deploy(long definitionId) throws Exception {
        ok(callAsSuperAdmin(post(DEFS_URL + "/" + definitionId + "/deploy")), "部署应成功");
    }

    /** 建草稿+部署直达 DEPLOYED。 */
    private long createAndDeploy(String key, String xml) throws Exception {
        long id = createDraft(key, key + "-name", xml);
        deploy(id);
        assertThat(definitionStatus(id)).as("部署后应 DEPLOYED").isEqualTo("DEPLOYED");
        return id;
    }

    /**
     * AC-2 反例统一驱动：保存+部署必须被拒（保存前置校验或部署校验任一形态），且定义不得进入
     * DEPLOYED；结构校验失败 wire 码=VALIDATION_FAILED（保存期 XML 解析失败允许 XML_INVALID），
     * 预期违规标记随 Result.data 逐条明细返回（marker=null 时不断言明细形状）。
     */
    private void assertDeployRejected(String key, String xml, String expectedMarker) throws Exception {
        long id = createDraft(key, key + "-name", xml);
        JsonNode saveResp = callAsSuperAdmin(put(DEFS_URL + "/" + id + "/xml")
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(bodyNode().put("bpmnXml", xml))));
        JsonNode failBody;
        if (!saveResp.path("success").asBoolean()) {
            failBody = saveResp;
            assertThat(saveResp.path("code").asText())
                    .as("保存期校验拒绝应为 XML_INVALID 或 VALIDATION_FAILED")
                    .isIn(WorkflowErrorCodes.WORKFLOW_XML_INVALID,
                            WorkflowErrorCodes.WORKFLOW_VALIDATION_FAILED);
        } else {
            failBody = callAsSuperAdmin(post(DEFS_URL + "/" + id + "/deploy"));
            bizFailCode(failBody, WorkflowErrorCodes.WORKFLOW_VALIDATION_FAILED,
                    "结构校验规则应拒绝部署");
        }
        if (expectedMarker != null) {
            assertThat(failBody.path("data").toString())
                    .as("校验失败应逐条返回明细（含违规标记 %s）：%s", expectedMarker, failBody)
                    .contains(expectedMarker);
        }
        assertThat(definitionStatus(id)).as("被拒定义不得进入 DEPLOYED").isEqualTo("DRAFT");
    }

    // ---------- 实例/任务夹具支撑 ----------

    private JsonNode startInstance(long definitionId, String businessKey, ObjectNode variables) throws Exception {
        return startInstanceAs("1", "default", Set.of("super_admin"), definitionId, businessKey, variables);
    }

    private JsonNode startInstanceAs(String userId, String tenant, Set<String> roles,
                                     long definitionId, String businessKey, ObjectNode variables) throws Exception {
        ObjectNode body = bodyNode();
        body.put("definitionId", String.valueOf(definitionId));
        body.put("businessKey", businessKey);
        if (variables != null) {
            body.set("variables", variables);
        }
        MvcResult result = performAs(userId, tenant, roles, post(INSTANCES_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus())
                .as("启动请求不应 5xx：%s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    private JsonNode instanceOp(String userId, String tenant, Set<String> roles, long instanceId,
                                String op, ObjectNode payload) throws Exception {
        MockHttpServletRequestBuilder builder = post(INSTANCES_URL + "/" + instanceId + "/" + op)
                .contentType(JSON_TYPE)
                .content(payload == null ? "{}" : JSON.writeValueAsString(payload));
        MvcResult result = performAs(userId, tenant, roles, builder);
        assertThat(result.getResponse().getStatus())
                .as("实例操作不应 5xx：%s -> %s", op,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    /** 任务操作（complete/reject/claim/unclaim/transfer/delegate）。 */
    private JsonNode taskOp(String userId, String tenant, Set<String> roles, long taskId,
                            String op, ObjectNode payload) throws Exception {
        MockHttpServletRequestBuilder builder = post(TASKS_URL + "/" + taskId + "/" + op)
                .contentType(JSON_TYPE)
                .content(payload == null ? "{}" : JSON.writeValueAsString(payload));
        MvcResult result = performAs(userId, tenant, roles, builder);
        assertThat(result.getResponse().getStatus())
                .as("任务操作不应 5xx：%s -> %s", op,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isLessThan(500);
        return bodyOf(result);
    }

    /** 任务完成（严格 assignee 语义，不自动认领；form 可空）。 */
    private JsonNode completeTask(String userId, String tenant, Set<String> roles, long taskId,
                                  ObjectNode form) throws Exception {
        ObjectNode body = bodyNode();
        if (form != null) {
            body.set("form", form);
        }
        return taskOp(userId, tenant, roles, taskId, "complete", body);
    }

    private List<JsonNode> todoOf(String userId, String tenant, Set<String> roles) throws Exception {
        JsonNode resp = bodyOf(performAs(userId, tenant, roles,
                get(TASKS_URL + "/todo?page=1&size=50")));
        ok(resp, "todo 查询应成功");
        return dataItems(resp);
    }

    private boolean todoContains(List<JsonNode> items, String nodeId) {
        return items.stream().anyMatch(item -> nodeId.equals(item.path("nodeId").asText(null)));
    }

    /** done 列表是否含 nodeId。 */
    private boolean doneContains(String userId, String nodeId) throws Exception {
        JsonNode resp = bodyOf(performAs(userId, "default", Set.of(),
                get(TASKS_URL + "/done?page=1&size=50")));
        if (!resp.path("success").asBoolean()) {
            return false;
        }
        return dataItems(resp).stream()
                .anyMatch(item -> nodeId.equals(item.path("nodeId").asText(null)));
    }

    /** initiated 列表是否含 businessKey（宽容解析）。 */
    private boolean initiatedContains(String userId, String businessKey) throws Exception {
        JsonNode resp = bodyOf(performAs(userId, "default", Set.of(),
                get(INSTANCES_URL + "/initiated?page=1&size=50")));
        if (!resp.path("success").asBoolean()) {
            return false;
        }
        return dataItems(resp).stream()
                .anyMatch(item -> businessKey.equals(item.path("businessKey").asText(null)));
    }

    // ---------- DB 夹具读取（契约确认②列名；库中真实状态推演，pitfalls ⑥） ----------

    private long definitionIdOf(String key, int version) {
        // 业务版本列=deploy_version（V1 DDL：AuditBase 乐观锁占名 version，业务版本另列承载）
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_definition where process_key = ? and deploy_version = ? "
                        + "order by id desc", Long.class, key, version);
        assertThat(ids).as("定义 %s v%d 应已落库", key, version).isNotEmpty();
        return ids.get(0);
    }

    private Long findLatestDefinitionId(String key) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_definition where process_key = ? "
                        + "order by deploy_version desc, id desc", Long.class, key);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String definitionStatus(long definitionId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select status from wf_process_definition where id = ?", String.class, definitionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long findInstanceId(String businessKey) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_process_instance where business_key = ? order by id desc",
                Long.class, businessKey);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String instanceStatus(long instanceId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select status from wf_process_instance where id = ?", String.class, instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String instanceSuspendReason(long instanceId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select suspend_reason from wf_process_instance where id = ?", String.class, instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 实例在指定节点的未终结任务（CREATED/CLAIMED），最新优先（驳回再生成轮次取新）。 */
    private Long findOpenTaskId(long instanceId, String nodeId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from wf_task where instance_id = ? and node_id = ? "
                        + "and status in ('CREATED','CLAIMED') order by id desc",
                Long.class, instanceId, nodeId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String taskStatus(long taskId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select status from wf_task where id = ?", String.class, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String taskAssignee(long taskId) {
        List<String> rows = jdbcTemplate.queryForList(
                "select assignee from wf_task where id = ?", String.class, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 实例某节点处于某状态集合的任务行数（statusIn 为已引号状态字面量集合）。 */
    private long taskCount(long instanceId, String nodeId, String statusIn) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from wf_task where instance_id = ? and node_id = ? and status in ("
                        + statusIn + ")", Long.class, instanceId, nodeId);
        return count == null ? 0L : count;
    }

    /** 实例活跃 execution 计数（按 node_id 是否落于给定集合内/外二分）。 */
    private long activeExecutionCount(long instanceId, boolean insideNodes, String... nodeIds) {
        String in = "'" + String.join("','", nodeIds) + "'";
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from wf_execution where instance_id = ? and is_active and node_id "
                        + (insideNodes ? "in (" + in + ")" : "not in (" + in + ")"),
                Long.class, instanceId);
        return count == null ? 0L : count;
    }

    private List<String> historyActions(long instanceId) {
        return jdbcTemplate.queryForList(
                "select action from wf_history_activity where instance_id = ? order by id",
                String.class, instanceId);
    }

    private long historyTotalCount(long instanceId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from wf_history_activity where instance_id = ?",
                Long.class, instanceId);
        return count == null ? 0L : count;
    }

    private long historyCount(long instanceId, String action) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from wf_history_activity where instance_id = ? and action = ?",
                Long.class, instanceId, action);
        return count == null ? 0L : count;
    }

    /** 变量值（jsonb/TEXT 双兼容 ::text 投影），空缺返回 null。 */
    private String variableText(long instanceId, String name) {
        List<String> rows = jdbcTemplate.queryForList(
                "select value::text from wf_variable where instance_id = ? and name = ?",
                String.class, instanceId, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** timer 行：id/status/boundary_event_id/scheduler_job_key/due_epoch。 */
    private Map<String, Object> timerRow(long instanceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, status, boundary_event_id, scheduler_job_key, "
                        + "extract(epoch from due_at) as due_epoch from wf_timer_job where instance_id = ? "
                        + "order by id desc", instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- 轮询支撑（pitfalls 15 口径：自旋 + 足够余量，不 sleep 死等） ----------

    private static boolean await(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleepQuietly(100);
        }
        return condition.getAsBoolean();
    }

    /** 观察窗：windowMs 内条件持续为真才通过（负例/稳定性断言口径）。 */
    private static boolean staysTrueFor(long windowMs, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < end) {
            if (!condition.getAsBoolean()) {
                return false;
            }
            sleepQuietly(100);
        }
        return condition.getAsBoolean();
    }

    /** 通用条件等待（返回首个满足条件的观测值，超时返回末次观测，可能为 null）。 */
    private <T> T awaitCondition(long timeoutMs, Supplier<T> observer) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        T last = observer.get();
        while (System.currentTimeMillis() < deadline) {
            if (last != null) {
                return last;
            }
            sleepQuietly(100);
            last = observer.get();
        }
        return last;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 等待实例在指定节点出现未终结任务（自旋）。 */
    private Long awaitOpenTask(long instanceId, String nodeId, long timeoutMs) {
        await(timeoutMs, () -> findOpenTaskId(instanceId, nodeId) != null);
        return findOpenTaskId(instanceId, nodeId);
    }

    private static final Pattern DELEGATE_PATH_PATTERN = Pattern.compile(Pattern.quote(DELEGATE_PATH));

    /** 委托下发请求（路径+方法按协议过滤）。 */
    private List<RecordedRequest> delegationDispatches() {
        List<RecordedRequest> hits = new ArrayList<>();
        for (RecordedRequest request : SERVICE_STUB.requests()) {
            if (DELEGATE_PATH_PATTERN.matcher(request.path()).matches()
                    && "POST".equals(request.method())) {
                hits.add(request);
            }
        }
        return hits;
    }

    /** 等待第 n 次委托下发（1 起）。 */
    private RecordedRequest awaitDelegation(int nth, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<RecordedRequest> all = delegationDispatches();
            if (all.size() >= nth) {
                return all.get(nth - 1);
            }
            sleepQuietly(100);
        }
        return null;
    }

    /** 下发协议体断言（ServiceTaskDispatchPayload 字段域：executionId/instanceId/businessKey/
     * nodeId/serviceRef/callbackPath/traceId）。 */
    private void assertDispatchPayload(String payload, String businessKey, String nodeId)
            throws IOException {
        JsonNode body = JSON.readTree(payload);
        assertThat(body.path("businessKey").asText())
                .as("下发体 businessKey：%s", payload).isEqualTo(businessKey);
        assertThat(body.path("nodeId").asText())
                .as("下发体 nodeId（ServiceTask 节点）：%s", payload).isEqualTo(nodeId);
        assertThat(body.path("serviceRef").asText())
                .as("下发体 serviceRef：%s", payload).isEqualTo(SERVICE_APP);
        assertThat(body.path("callbackPath").asText())
                .as("下发体 callbackPath：%s", payload).isEqualTo(DELEGATE_PATH);
        assertThat(body.path("executionId").asLong())
                .as("下发体 executionId 应非零：%s", payload).isPositive();
        assertThat(body.path("instanceId").asLong())
                .as("下发体 instanceId 应非零：%s", payload).isPositive();
        assertThat(body.path("traceId").asText())
                .as("下发体 traceId 非空：%s", payload).isNotBlank();
    }

    /** 下发体 executionId 反解（回调 URL 用）。 */
    private String executionIdOfDispatch(RecordedRequest dispatch) throws IOException {
        JsonNode body = JSON.readTree(dispatch.body());
        assertThat(body.path("executionId").asLong())
                .as("下发体应含 executionId：%s", dispatch.body()).isPositive();
        return String.valueOf(body.path("executionId").asLong());
    }

    private JsonNode fireTimer(long timerJobId) throws Exception {
        return internalCall(post(TIMER_FIRE_URL.formatted(timerJobId))
                .contentType(JSON_TYPE)
                .content("{}"));
    }

    private JsonNode serviceCallback(String executionId, boolean success, String error) throws Exception {
        ObjectNode body = bodyNode();
        body.put("success", success);
        if (error != null) {
            body.put("error", error);
        }
        return internalCall(post(SVC_CALLBACK_URL.formatted(executionId))
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(body)));
    }

    /** 等待调度中心注册请求（body 含 jobKey=wf-timer:）。 */
    private RecordedRequest awaitSchedulerRegister(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RecordedRequest request : SCHEDULER_STUB.requests()) {
                if ("POST".equals(request.method())
                        && request.path().startsWith(SCHEDULER_REGISTER_PATH)
                        && request.body().contains("wf-timer:")) {
                    return request;
                }
            }
            sleepQuietly(100);
        }
        return null;
    }

    /** 等待调度中心注销请求（DELETE /internal/v1/scheduler/jobs/{jobKey}）。 */
    private RecordedRequest awaitSchedulerDeregister(String jobKey, long timeoutMs) {
        String expectedPath = SCHEDULER_REGISTER_PATH + "/" + jobKey;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RecordedRequest request : SCHEDULER_STUB.requests()) {
                if ("DELETE".equals(request.method()) && expectedPath.equals(request.path())) {
                    return request;
                }
            }
            sleepQuietly(100);
        }
        return null;
    }

    // ---------- AC-1：V1 七表 + 唯一索引 + Flyway 成功 ----------

    @Test
    void should_prepare_seven_tables_and_unique_indexes_when_v1_migrated() {
        // ① V1 七表就绪（确认②）
        for (String table : List.of("wf_process_definition", "wf_process_instance", "wf_execution",
                "wf_task", "wf_variable", "wf_history_activity", "wf_timer_job")) {
            Long rows = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
            assertThat(rows).as("V1 表 %s 应就绪", table).isNotNull();
        }

        // ② 契约钉死唯一索引：uk(key,version) / uk(instance_id,name) / uk(scheduler_job_key)
        //   （catalog 断言不猜列可空性；归一化后子串匹配）
        List<String> defIndexes = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where schemaname = current_schema() "
                        + "and tablename = 'wf_process_definition'", String.class);
        assertThat(defIndexes).as("wf_process_definition 应有 uk(process_key,deploy_version)（设计 §3.3 记名 key/version，V1 DDL 列名 deploy_version 承载业务版本）")
                .anySatisfy(def -> {
                    String normalized = def.toLowerCase().replace(" ", "");
                    assertThat(normalized).contains("unique")
                            .contains("process_key").contains("deploy_version");
                });
        List<String> varIndexes = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where schemaname = current_schema() "
                        + "and tablename = 'wf_variable'", String.class);
        assertThat(varIndexes).as("wf_variable 应有 uk(instance_id,name)")
                .anySatisfy(def -> {
                    String normalized = def.toLowerCase().replace(" ", "");
                    assertThat(normalized).contains("unique").contains("instance_id").contains("name");
                });
        List<String> timerIndexes = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where schemaname = current_schema() "
                        + "and tablename = 'wf_timer_job'", String.class);
        assertThat(timerIndexes).as("wf_timer_job 应有 uk(scheduler_job_key)")
                .anySatisfy(def -> {
                    String normalized = def.toLowerCase().replace(" ", "");
                    assertThat(normalized).contains("unique").contains("scheduler_job_key");
                });

        // ③ Flyway V1 执行成功且无失败迁移（AC-1 迁移幂等面：V1 DDL 由 Flyway 一次性执行；
        //    V27 种子幂等/坑 16 水位校准由 platform-system 侧适配承载，见交付报告映射）
        Long v1Rows = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Long.class);
        assertThat(v1Rows).as("workflow V1 迁移应已执行成功").isGreaterThan(0L);
        Long failedRows = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where not success", Long.class);
        assertThat(failedRows).as("迁移历史不得有失败条目").isZero();
    }

    // ---------- AC-2：定义生命周期正向（草稿→存 XML→部署→详情→禁用→新实例拒绝/运行不受影响） ----------

    @Test
    void should_manage_definition_lifecycle_when_draft_saved_deployed_and_disabled() throws Exception {
        String key = uniq("lifecycle");

        // ① 创建草稿（无 XML）→ DRAFT
        long defId = createDraft(key, key + "-name", null);
        assertThat(definitionStatus(defId)).as("创建默认 DRAFT").isEqualTo("DRAFT");

        // ② PUT /{id}/xml 保存（草稿态）→ bpmn_xml 落库
        saveXml(defId, bpmn(key, XOR_BODY));
        String storedXml = jdbcTemplate.queryForObject(
                "select bpmn_xml from wf_process_definition where id = ?", String.class, defId);
        assertThat(storedXml).as("bpmn_xml 应落库").contains("task-big");

        // ③ GET /{id} 详情含 bpmnXml（契约 §API）
        JsonNode detail = ok(callAsSuperAdmin(get(DEFS_URL + "/" + defId)), "定义详情应成功");
        assertThat(detail.path("data").path("bpmnXml").asText())
                .as("详情应回传 bpmnXml").contains("task-big");

        // ④ 未存 XML 的草稿不得部署（先建一个纯草稿）
        String emptyKey = uniq("emptydraft");
        long emptyId = createDraft(emptyKey, emptyKey + "-name", null);
        JsonNode emptyDeploy = callAsSuperAdmin(post(DEFS_URL + "/" + emptyId + "/deploy"));
        bizFail(emptyDeploy, "无 XML 草稿部署应拒绝");

        // ⑤ 部署 → DEPLOYED（编译缓存生成由启动可达性承载）
        deploy(defId);
        assertThat(definitionStatus(defId)).as("部署后 DEPLOYED").isEqualTo("DEPLOYED");

        // ⑥ 启动实例（DEPLOYED 守卫正例）
        String bk = uniq("lifecycle-inst");
        ok(startInstance(defId, bk, vars("amount", 2000)), "已部署定义应可启动");

        // ⑦ disable → DISABLED；新实例拒绝（DEFINITION_NOT_DEPLOYED）；DISABLED 定义保存 XML 拒绝
        ok(callAsSuperAdmin(post(DEFS_URL + "/" + defId + "/disable")), "禁用应成功");
        assertThat(definitionStatus(defId)).as("禁用后 DISABLED").isEqualTo("DISABLED");
        JsonNode restart = callAsSuperAdmin(post(INSTANCES_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(bodyNode()
                        .put("definitionId", String.valueOf(defId))
                        .put("businessKey", uniq("after-disable")))));
        bizFailCode(restart, WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_DEPLOYED,
                "DISABLED 定义新实例应拒绝");
        JsonNode disabledSave = callAsSuperAdmin(put(DEFS_URL + "/" + defId + "/xml")
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(bodyNode().put("bpmnXml", bpmn(key, XOR_BODY)))));
        bizFailCode(disabledSave, WorkflowErrorCodes.WORKFLOW_DEFINITION_DISABLED,
                "DISABLED 定义保存 XML 应拒绝");

        // ⑧ 运行实例不受影响：完成其任务照常推进（契约 §API disable 语义）
        long instanceId = findInstanceId(bk);
        Long bigTask = findOpenTaskId(instanceId, "task-big");
        assertThat(bigTask).as("运行实例任务应存在").isNotNull();
        ok(completeTask("1001", "default", Set.of(), bigTask, null), "运行实例任务完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("运行实例应照常完成，实际=%s", instanceStatus(instanceId)).isTrue();

        // ⑨ 分页：keyword/status 过滤（key+version 排序契约 §API；多版本排序在 AC-13 用例承载）
        JsonNode page = ok(callAsSuperAdmin(get(DEFS_URL + "?page=1&size=50&keyword=" + key)),
                "定义分页应成功");
        List<JsonNode> items = dataItems(page);
        assertThat(items).as("keyword 过滤应命中本 key").isNotEmpty();
        assertThat(items).as("keyword 过滤结果应只含本 key")
                .allSatisfy(item -> assertThat(item.path("key").asText()).isEqualTo(key));
        JsonNode statusPage = ok(callAsSuperAdmin(
                        get(DEFS_URL + "?page=1&size=50&keyword=" + key + "&status=DISABLED")),
                "status 过滤应成功");
        assertThat(dataItems(statusPage))
                .as("status=DISABLED 过滤应命中")
                .anySatisfy(item -> assertThat(item.path("status").asText()).isEqualTo("DISABLED"));
    }

    // ---------- AC-2：全元素组合正向（一期全部元素可部署可执行；AC-13 缓存生成正例锚） ----------

    @Test
    void should_deploy_and_execute_kitchen_sink_process_when_all_phase1_elements_valid()
            throws Exception {
        String key = uniq("kitchen");
        long defId = createAndDeploy(key, bpmn(key, KITCHEN_SINK_BODY));
        String bk = uniq("kitchen-inst");

        // ① 以 u-sink（super_admin 角色）启动：fork 双支展开
        JsonNode startResp = startInstanceAs("2101", "default", Set.of("super_admin"),
                defId, bk, null);
        ok(startResp, "全元素模型应可启动");
        long instanceId = findInstanceId(bk);
        assertThat(instanceId).as("实例应落库").isNotNull();

        // ② 候选任务可见（ALL 策略一期）+ ServiceTask 委托下发（协议体断言）
        assertThat(await(6_000, () -> findOpenTaskId(instanceId, "task-cand") != null))
                .as("候选任务应创建").isTrue();
        assertThat(todoContains(todoOf("2101", "default", Set.of("super_admin")), "task-cand"))
                .as("候选成员 todo 应可见").isTrue();
        RecordedRequest dispatch = awaitDelegation(1, 8_000);
        assertThat(dispatch).as("ServiceTask 应向目标端点下发委托").isNotNull();
        assertDispatchPayload(dispatch.body(), bk, "svc");

        // ③ 委托回调成功 → svc 支到达 join
        ok(serviceCallback(executionIdOfDispatch(dispatch), true, null), "委托成功回调应受理");

        // ④ 完成候选任务（候选可办）→ join 聚合 → 子流程内 ${initiator} 任务
        Long candTask = findOpenTaskId(instanceId, "task-cand");
        JsonNode candComplete = completeTask("2101", "default", Set.of("super_admin"), candTask, null);
        if (!candComplete.path("success").asBoolean()) {
            // 兼容实现要求先认领：候选 claim 后完成（AC-5 claim 语义，不判失败）
            ok(taskOp("2101", "default", Set.of("super_admin"), candTask, "claim", null),
                    "候选认领应成功");
            ok(completeTask("2101", "default", Set.of("super_admin"), candTask, null),
                    "认领后完成应成功");
        } else {
            ok(candComplete, "候选直接完成应成功（ALL 策略可见可办）");
        }

        // ⑤ 子流程 ${initiator} 任务 → 完成 → hasRole 网关取真分支（内置函数路由）
        Long subTask = awaitOpenTask(instanceId, "sub-task", 8_000);
        assertThat(subTask).as("join 聚合后应进入子流程任务").isNotNull();
        assertThat(taskAssignee(subTask)).as("子流程 ${initiator} 指派应解析为发起人").isEqualTo("2101");
        ok(completeTask("2101", "default", Set.of("super_admin"), subTask, null),
                "子任务完成应成功");
        Long adminTail = awaitOpenTask(instanceId, "task-tail-admin", 8_000);
        assertThat(adminTail).as("hasRole('super_admin') 应取真分支（admin 尾任务）").isNotNull();

        // ⑥ 完成 admin 尾任务 → 实例 COMPLETED；实例完成 → 边界 timer 注销（CANCELLED）
        ok(completeTask("2102", "default", Set.of(), adminTail, null), "尾任务完成应成功");
        assertThat(await(6_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("全元素模型应执行到终点，实际=%s", instanceStatus(instanceId)).isTrue();
        Map<String, Object> timer = timerRow(instanceId);
        assertThat(timer).as("边界 timer 行应已落库").isNotNull();
        assertThat(await(6_000, () -> "CANCELLED".equals(timerRow(instanceId).get("status"))))
                .as("实例完成后边界 timer 应 CANCELLED，实际=%s", timerRow(instanceId))
                .isTrue();
    }

    // ---------- AC-2：部署校验规则反例（逐规则；正例由生命周期/全元素两条部署用例承载） ----------

    @Test
    void should_reject_deploy_when_start_event_missing_or_duplicated() throws Exception {
        // start/end 恰一规则反例（明细形状不钉，只钉拒绝与 DRAFT 不变）
        assertDeployRejected(uniq("no-start"), INVALID_NO_START, null);
        assertDeployRejected(uniq("two-start"), INVALID_DOUBLE_START, null);
    }

    @Test
    void should_reject_deploy_when_sequence_flow_dangles_to_unknown_node() throws Exception {
        assertDeployRejected(uniq("dangling"), INVALID_DANGLING_FLOW, "ghost-node");
    }

    @Test
    void should_reject_deploy_when_gateway_flows_miss_conditions_without_default() throws Exception {
        // 明细以流出连线 id 逐条承载（实证：GATEWAY_CONDITIONS 明细 nodeId=连线 id）
        assertDeployRejected(uniq("gw-nocond"), INVALID_GATEWAY_UNCONDITIONAL, "g1");
    }

    @Test
    void should_reject_deploy_when_node_ids_duplicated() throws Exception {
        assertDeployRejected(uniq("dup-id"), INVALID_DUPLICATE_IDS, "dup");
    }

    @Test
    void should_reject_deploy_when_spel_expression_invalid_or_forbidden() throws Exception {
        // ① SpEL 语法错 → 部署拒绝（§5「SpEL 语法预解析通过」钉死）
        assertDeployRejected(uniq("spel-bad"), INVALID_SPEL_SYNTAX, "g1");
        // ② 类型引用红线（§3.5「SimpleEvaluationContext 禁类型引用/Bean 访问」——红线钉在求值
        //    上下文能力，拦截点二形态宽容）：部署期预解析拒绝（严格形态）或部署放行+运行期
        //    求值拒绝（SimpleEvaluationContext 兜底；g1 为网关首流出，启动即求值必触红线）。
        //    若两形态皆不成立（部署放行且启动成功）= 安全红线失守，红上报。
        long typeDefId = createDraft(uniq("spel-type"), "spel-type-name", INVALID_SPEL_TYPE_REFERENCE);
        saveXml(typeDefId, INVALID_SPEL_TYPE_REFERENCE);
        JsonNode deployResp = callAsSuperAdmin(post(DEFS_URL + "/" + typeDefId + "/deploy"));
        if (!deployResp.path("success").asBoolean()) {
            bizFailCode(deployResp, WorkflowErrorCodes.WORKFLOW_VALIDATION_FAILED,
                    "类型引用部署期拦截应为 VALIDATION_FAILED");
            assertThat(definitionStatus(typeDefId)).isEqualTo("DRAFT");
            return;
        }
        // 部署放行形态：运行期求值必须拒绝（T(...) 在 SimpleEvaluationContext 下不可求值）
        String bk = uniq("spel-type-inst");
        ObjectNode startBody = bodyNode();
        startBody.put("definitionId", String.valueOf(typeDefId));
        startBody.put("businessKey", bk);
        JsonNode startResp = bodyOf(performAs("1", "default", Set.of("super_admin"),
                post(INSTANCES_URL).contentType(JSON_TYPE)
                        .content(JSON.writeValueAsString(startBody))));
        assertThat(startResp.path("success").asBoolean())
                .as("类型引用条件流（网关首流出）启动求值必须被 SimpleEvaluationContext 拒绝：%s",
                        startResp)
                .isFalse();
    }

    @Test
    void should_reject_deploy_when_usertask_has_no_assignment_rule() throws Exception {
        assertDeployRejected(uniq("task-norule"), INVALID_USER_TASK_NO_RULE, "t1");
    }

    @Test
    void should_reject_deploy_when_service_task_misses_service_ref_or_callback_path() throws Exception {
        assertDeployRejected(uniq("svc-noref"), INVALID_SERVICE_NO_REF, "svc");
        assertDeployRejected(uniq("svc-nopath"), INVALID_SERVICE_NO_PATH, "svc");
    }

    @Test
    void should_reject_deploy_when_boundary_event_not_attached() throws Exception {
        assertDeployRejected(uniq("boundary-free"), INVALID_BOUNDARY_UNATTACHED, "timer-free");
    }

    @Test
    void should_reject_deploy_when_end_event_missing() throws Exception {
        assertDeployRejected(uniq("no-end"), INVALID_NO_END, null);
    }

    @Test
    void should_reject_duplicate_definition_key_when_creating_draft() throws Exception {
        String key = uniq("dup-key");
        createDraft(key, key + "-name", bpmn(key, SINGLE_TASK_BODY));
        ObjectNode again = bodyNode();
        again.put("key", key);
        again.put("name", key + "-again");
        JsonNode resp = callAsSuperAdmin(post(DEFS_URL)
                .contentType(JSON_TYPE)
                .content(JSON.writeValueAsString(again)));
        bizFailCode(resp, WorkflowErrorCodes.WORKFLOW_KEY_DUPLICATE, "重复 key 创建草稿应拒绝");
    }

    // ---------- AC-3：排他网关（首真分支 / default 兜底 / 无条件异常挂起） ----------

    @Test
    void should_route_first_matching_condition_when_exclusive_gateway_evaluates() throws Exception {
        String key = uniq("xor");
        long defId = createAndDeploy(key, bpmn(key, XOR_BODY));

        // 分支一：amount=2000 → 首个为真条件 task-big（固定指派）
        String bk1 = uniq("xor-big");
        ok(startInstance(defId, bk1, vars("amount", 2000)), "分支一启动应成功");
        long inst1 = findInstanceId(bk1);
        Long bigTask = awaitOpenTask(inst1, "task-big", 6_000);
        assertThat(bigTask).as("amount=2000 应取首真分支 task-big").isNotNull();
        assertThat(taskCount(inst1, "task-mid", "'CREATED','CLAIMED'"))
                .as("不得同时落低优先分支任务").isZero();
        assertThat(taskAssignee(bigTask)).as("固定指派 acme:assignee 生效").isEqualTo("1001");
        ok(completeTask("1001", "default", Set.of(), bigTask, null), "分支一完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(inst1))))
                .as("分支一到终点").isTrue();

        // 分支二：amount=500 → 第二条件为真 task-mid（${initiator} 指派）
        String bk2 = uniq("xor-mid");
        ok(startInstanceAs("1002", "default", Set.of(), defId, bk2, vars("amount", 500)),
                "分支二启动应成功");
        long inst2 = findInstanceId(bk2);
        Long midTask = awaitOpenTask(inst2, "task-mid", 6_000);
        assertThat(midTask).as("amount=500 应取第二真分支 task-mid").isNotNull();
        assertThat(taskAssignee(midTask)).as("${initiator} 指派应解析为发起人").isEqualTo("1002");
        assertThat(todoContains(todoOf("1002", "default", Set.of()), "task-mid"))
                .as("发起人 todo 应可见 ${initiator} 任务").isTrue();
    }

    @Test
    void should_fall_back_to_default_flow_when_no_condition_matches() throws Exception {
        String key = uniq("xor-default");
        long defId = createAndDeploy(key, bpmn(key, XOR_BODY));

        String bk = uniq("xor-small");
        ok(startInstance(defId, bk, vars("amount", 50)), "default 分支启动应成功");
        long instanceId = findInstanceId(bk);
        Long smallTask = awaitOpenTask(instanceId, "task-small", 6_000);
        assertThat(smallTask).as("无真条件应走 default 流 task-small").isNotNull();
        assertThat(taskCount(instanceId, "task-big", "'CREATED','CLAIMED'"))
                .as("default 分支不得落条件分支任务").isZero();

        // 候选角色任务：候选可见，认领后 assignee 生效（AC-5 claim 联动）
        assertThat(todoContains(todoOf("1101", "default", Set.of("wf-role-a")), "task-small"))
                .as("候选角色成员 todo 应可见").isTrue();
        ok(taskOp("1101", "default", Set.of("wf-role-a"), smallTask, "claim", null),
                "候选认领应成功");
        assertThat(taskStatus(smallTask)).as("认领后 CLAIMED").isEqualTo("CLAIMED");
        assertThat(taskAssignee(smallTask)).as("claim 后 assignee 生效").isEqualTo("1101");
        ok(completeTask("1101", "default", Set.of("wf-role-a"), smallTask, null),
                "认领人完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("default 分支到终点").isTrue();
    }

    @Test
    void should_park_instance_without_task_when_gateway_has_no_route() throws Exception {
        // A-8 宽容：bizFail+实例承载（对齐 03-0005「失败+实例承载」先例）或 ok+execution 停网关；
        // 共同不变量=实例存在、无任务产生、不终态（设计 §2「抛 no-route 异常并挂起该 execution」）。
        // 启动响应不做 <500 前置守卫（no-route 形态实现可能以 500 原样抛出——语义断言以库内状态为准）
        String key = uniq("xor-noroute");
        long defId = createAndDeploy(key, bpmn(key, XOR_NO_DEFAULT_BODY));

        String bk = uniq("noroute");
        ObjectNode body = bodyNode();
        body.put("definitionId", String.valueOf(defId));
        body.put("businessKey", bk);
        body.set("variables", vars("amount", 50));
        JsonNode startResp = bodyOf(performAs("1", "default", Set.of("super_admin"),
                post(INSTANCES_URL).contentType(JSON_TYPE)
                        .content(JSON.writeValueAsString(body))));
        Long instanceId = findInstanceId(bk);
        if (instanceId == null) {
            // 形态三：命令整体回滚（确认③「命令失败整体回滚」口径）——无实例承载即无部分推进，
            // AC-12 语义自洽；spec §2「挂起该 execution」与此存在张力，归 TL 裁决（A-8）
            assertThat(startResp.path("success").asBoolean())
                    .as("回滚形态下启动应业务失败：%s", startResp).isFalse();
            assertThat(startResp.path("code").asText())
                    .as("无路失败信封 code 应≠0").isNotEqualTo("0");
            return;
        }
        if (!startResp.path("success").asBoolean()) {
            assertThat(startResp.path("code").asText())
                    .as("无路失败信封 code 应≠0").isNotEqualTo("0");
        }
        assertThat(staysTrueFor(2_000, () -> taskCount(instanceId, "task-x",
                "'CREATED','CLAIMED','REJECTED'")
                + taskCount(instanceId, "task-y", "'CREATED','CLAIMED','REJECTED'") == 0))
                .as("无路不得产生任何任务").isTrue();
        String status = instanceStatus(instanceId);
        assertThat(status).as("无路实例不得终态（实际=%s）", status)
                .isNotIn("COMPLETED", "TERMINATED");
    }

    // ---------- AC-4：并行 fork-join（全到达聚合 / 部分到达不推进） ----------

    @Test
    void should_aggregate_join_and_continue_when_all_parallel_branches_complete() throws Exception {
        String key = uniq("parallel");
        long defId = createAndDeploy(key, bpmn(key, PARALLEL_BODY));
        String bk = uniq("parallel-inst");
        ok(startInstance(defId, bk, null), "并行启动应成功");
        long instanceId = findInstanceId(bk);

        // ① fork 3 支并发 execution（is_concurrent 子执行体）+ 3 任务
        assertThat(await(6_000, () -> findOpenTaskId(instanceId, "task-a") != null
                && findOpenTaskId(instanceId, "task-b") != null
                && findOpenTaskId(instanceId, "task-c") != null))
                .as("fork 后三支任务应齐备").isTrue();
        long concurrentChildren = activeExecutionCount(instanceId, true,
                "task-a", "task-b", "task-c", "join");
        assertThat(concurrentChildren).as("fork 应产生并发子 execution（≥3）").isGreaterThanOrEqualTo(3);

        // ② 部分到达：完成 A、B → join 不推进（无 task-after）
        ok(completeTask("2801", "default", Set.of(), findOpenTaskId(instanceId, "task-a"), null),
                "分支A完成应成功");
        ok(completeTask("2801", "default", Set.of(), findOpenTaskId(instanceId, "task-b"), null),
                "分支B完成应成功");
        assertThat(staysTrueFor(2_500, () -> findOpenTaskId(instanceId, "task-after") == null))
                .as("部分到达（2/3）不得聚合推进").isTrue();
        assertThat(instanceStatus(instanceId)).as("部分到达时实例仍运行").isEqualTo("RUNNING");

        // ③ 全到达：完成 C → join 聚合 → task-after 出现（最后到达者聚合继续）
        ok(completeTask("2801", "default", Set.of(), findOpenTaskId(instanceId, "task-c"), null),
                "分支C完成应成功");
        Long afterTask = awaitOpenTask(instanceId, "task-after", 8_000);
        assertThat(afterTask).as("全部到达后应聚合继续").isNotNull();
        ok(completeTask("2801", "default", Set.of(), afterTask, null), "汇合任务完成应成功");
        assertThat(await(6_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("并行模型到终点").isTrue();
    }

    // ---------- AC-5：指派规则各型（todo/done/initiated + claim 生效 + 租户隔离） ----------

    @Test
    void should_resolve_assignee_and_initiator_rules_when_user_task_created() throws Exception {
        // ① 固定指派：assignee todo 可见、非 assignee 不可见（SINGLE_TASK_BODY 固定指派=2501）
        String key = uniq("assign-fixed");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("fixed");
        ok(startInstance(defId, bk, null), "固定指派启动应成功");
        long instanceId = findInstanceId(bk);
        assertThat(await(6_000, () -> findOpenTaskId(instanceId, "task-only") != null))
                .as("固定指派任务应创建").isTrue();
        assertThat(todoContains(todoOf("2501", "default", Set.of()), "task-only"))
                .as("assignee todo 应可见").isTrue();
        assertThat(todoContains(todoOf("1099", "default", Set.of()), "task-only"))
                .as("非 assignee todo 不可见").isFalse();

        // ② ${initiator} 指派 + initiated（我发起）查询
        String initKey = uniq("assign-initiator");
        long initDef = createAndDeploy(initKey,
                bpmn(initKey, SINGLE_TASK_BODY.replace("2501", "${initiator}")));
        String bk2 = uniq("initiator");
        ok(startInstanceAs("1003", "default", Set.of(), initDef, bk2, null),
                "initiator 启动应成功");
        long inst2 = findInstanceId(bk2);
        Long initTask = awaitOpenTask(inst2, "task-only", 6_000);
        assertThat(initTask).as("${initiator} 任务应创建").isNotNull();
        assertThat(taskAssignee(initTask)).as("${initiator} 指派应解析为发起人")
                .isEqualTo("1003");
        assertThat(initiatedContains("1003", bk2))
                .as("initiated 应含我发起的实例").isTrue();
        assertThat(initiatedContains("1099", bk2))
                .as("initiated 不应含他人实例").isFalse();
    }

    @Test
    void should_offer_candidate_task_and_claim_when_candidate_matches() throws Exception {
        // candidateUsers 型（A-7：身份目录 WorkflowIdentityClient 已按契约桩定，候选面确定性可解析）
        String usersKey = uniq("cand-users");
        long usersDef = createAndDeploy(usersKey, bpmn(usersKey,
                CANDIDATE_USERS_BODY.formatted("2001,2002")));
        String usersBk = uniq("cand-users-inst");
        ok(startInstance(usersDef, usersBk, null), "candidateUsers 启动应成功");
        long usersInst = findInstanceId(usersBk);
        Long candTask = awaitOpenTask(usersInst, "task-cand", 6_000);
        assertThat(candTask).as("候选任务应创建（无固定 assignee）").isNotNull();
        assertThat(taskAssignee(candTask)).as("候选任务初始无 assignee").isNull();
        assertThat(todoContains(todoOf("2002", "default", Set.of()), "task-cand"))
                .as("candidateUsers 成员 todo 应可见").isTrue();
        assertThat(todoContains(todoOf("2099", "default", Set.of()), "task-cand"))
                .as("非候选 todo 不可见").isFalse();

        // claim → assignee 生效；已认领再 claim 拒绝；unclaim 回 CREATED；未认领 unclaim 拒绝
        ok(taskOp("2001", "default", Set.of(), candTask, "claim", null), "候选认领应成功");
        assertThat(taskStatus(candTask)).isEqualTo("CLAIMED");
        assertThat(taskAssignee(candTask)).as("claim 后 assignee 生效").isEqualTo("2001");
        bizFailCode(taskOp("2001", "default", Set.of(), candTask, "claim", null),
                WorkflowErrorCodes.WORKFLOW_TASK_STATE_CONFLICT, "已认领再 claim 应拒绝");
        ok(taskOp("2001", "default", Set.of(), candTask, "unclaim", null), "认领人 unclaim 应成功");
        assertThat(taskAssignee(candTask)).as("unclaim 后 assignee 应清空").isNull();
        assertThat(taskStatus(candTask)).as("unclaim 后回 CREATED").isEqualTo("CREATED");
        bizFailCode(taskOp("2001", "default", Set.of(), candTask, "unclaim", null),
                WorkflowErrorCodes.WORKFLOW_TASK_STATE_CONFLICT, "未认领 unclaim 应拒绝");
        ok(taskOp("2002", "default", Set.of(), candTask, "claim", null), "另一候选认领应成功");

        // 完成后 done 可见（AC-5 done 面）
        ok(completeTask("2002", "default", Set.of(), candTask, null), "候选完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(usersInst))))
                .as("候选实例到终点").isTrue();
        assertThat(doneContains("2002", "task-cand")).as("done 应含已完成任务").isTrue();
    }

    @Test
    void should_suspend_instance_when_no_assignee_resolved() throws Exception {
        // A-7/03-0005：候选解析为空 → 实例 SUSPENDED 承载（api 契约注释钉死；start 可能同步
        // 返回 409 信封——两形态皆断言实例 SUSPENDED 且无任务）
        String key = uniq("no-assignee");
        long defId = createAndDeploy(key, bpmn(key, CANDIDATE_BODY.formatted("wf-nobody-role")));
        String bk = uniq("no-assignee-inst");
        JsonNode startResp = startInstance(defId, bk, null);
        long instanceId = findInstanceId(bk);
        assertThat(instanceId).as("无候选人实例应承载入库").isNotNull();
        if (!startResp.path("success").asBoolean()) {
            assertThat(startResp.path("code").asText())
                    .as("无候选人失败信封应为 NO_ASSIGNEE_RESOLVED")
                    .isEqualTo(WorkflowErrorCodes.WORKFLOW_NO_ASSIGNEE_RESOLVED);
        }
        assertThat(await(6_000, () -> "SUSPENDED".equals(instanceStatus(instanceId))))
                .as("无候选人实例应 SUSPENDED，实际=%s", instanceStatus(instanceId)).isTrue();
        // 契约只钉「实例 SUSPENDED 承载」；任务行是否先建后挂起属实现形态——钉不可达语义：
        // 挂起态不得有认领任务，且对残留任务的任务操作必须被实例域阻断（02-0002）
        Long orphanTask = findOpenTaskId(instanceId, "task-cand");
        if (orphanTask != null) {
            assertThat(taskStatus(orphanTask)).as("挂起态不得存在认领任务").isEqualTo("CREATED");
            bizFailCode(completeTask("2599", "default", Set.of(), orphanTask, null),
                    WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "挂起实例的候选任务操作应被实例域阻断");
        }
    }

    @Test
    void should_resolve_department_candidates_when_candidate_depts_rule_matches() throws Exception {
        // A-7（已消解）：WorkflowIdentityClient 契约桩定 deptUserIds(10)={2401,2402} /
        // userDeptId(2401)=10——candidateDepts 正例走确定性断言（任务创建、候选可见、claim 生效）；
        // 解析空→NO_ASSIGNEE_RESOLVED 挂起形态由 no-assignee 用例（roleUserIds 空集）承载。
        String key = uniq("cand-depts");
        long defId = createAndDeploy(key, bpmn(key, CANDIDATE_DEPTS_BODY.formatted("10")));
        String bk = uniq("cand-depts-inst");
        ok(startInstance(defId, bk, null), "candidateDepts 启动应成功");
        long instanceId = findInstanceId(bk);
        assertThat(instanceId).as("candidateDepts 实例应承载").isNotNull();

        Long deptTask = awaitOpenTask(instanceId, "task-cand", 6_000);
        assertThat(deptTask).as("部门候选任务应创建（deptUserIds(10) 非空）").isNotNull();
        assertThat(todoContains(todoOf("2401", "default", Set.of()), "task-cand"))
                .as("部门成员（userDeptId=10）todo 应可见").isTrue();
        assertThat(todoContains(todoOf("1099", "default", Set.of()), "task-cand"))
                .as("非部门成员 todo 不可见").isFalse();
        ok(taskOp("2401", "default", Set.of(), deptTask, "claim", null), "部门候选认领应成功");
        assertThat(taskAssignee(deptTask)).as("claim 后 assignee 生效").isEqualTo("2401");
        ok(completeTask("2401", "default", Set.of(), deptTask, null), "部门候选完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("部门候选实例到终点").isTrue();
    }

    @Test
    void should_isolate_todo_by_tenant_when_multi_tenant_instances_exist() throws Exception {
        String key = uniq("tenant-iso");
        long defId = createAndDeploy(key, bpmn(key,
                SINGLE_TASK_BODY.replace("2501", "3201")));

        // tenant-a 实例 + tenant-b 实例（同定义、同 assignee 标记）
        String bkA = uniq("iso-a");
        ok(startInstanceAs("3202", "tenant-a", Set.of(), defId, bkA, null), "tenant-a 启动应成功");
        String bkB = uniq("iso-b");
        ok(startInstanceAs("3203", "tenant-b", Set.of(), defId, bkB, null), "tenant-b 启动应成功");
        long instB = findInstanceId(bkB);
        assertThat(await(6_000, () -> findOpenTaskId(instB, "task-only") != null))
                .as("tenant-b 任务应创建").isTrue();

        List<JsonNode> todoA = todoOf("3201", "tenant-a", Set.of());
        List<JsonNode> todoB = todoOf("3201", "tenant-b", Set.of());
        assertThat(todoContains(todoA, "task-only")).as("tenant-a 应见本租户任务").isTrue();
        assertThat(todoContains(todoB, "task-only")).as("tenant-b 应见本租户任务").isTrue();
        // 互不可见：todoA 项不含 tenant-b 实例任务（nodeId 同名，按任务 id 区分）
        long taskB = findOpenTaskId(instB, "task-only");
        assertThat(todoA.stream().noneMatch(item ->
                String.valueOf(taskB).equals(item.path("id").asText())))
                .as("tenant-a todo 不得含 tenant-b 任务行").isTrue();
    }

    // ---------- AC-6：任务操作状态机矩阵 ----------

    @Test
    void should_complete_task_and_advance_when_assignee_submits_form() throws Exception {
        String key = uniq("complete");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("complete-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);
        assertThat(task).isNotNull();

        // 表单完成（form_data jsonb 落库）→ 实例完成
        ObjectNode form = bodyNode();
        form.put("comment", "通过-142316");
        form.put("score", 100);
        ok(completeTask("2501", "default", Set.of(), task, form), "assignee 表单完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("完成最后任务实例应 COMPLETED").isTrue();
        String formData = jdbcTemplate.queryForObject(
                "select form_data::text from wf_task where id = ?", String.class, task);
        assertThat(formData).as("form_data 应落库").contains("通过-142316").contains("100");
        assertThat(taskStatus(task)).isEqualTo("COMPLETED");

        // 实例详情（实例+活跃节点+任务树+变量摘要，宽容形状）
        JsonNode detail = ok(callAsSuperAdmin(get(INSTANCES_URL + "/" + instanceId)), "实例详情应成功");
        assertThat(detail.path("data").toString())
                .as("实例详情应含业务键与终态").contains(bk).contains("COMPLETED");
    }

    @Test
    void should_reject_complete_when_operator_is_not_assignee() throws Exception {
        String key = uniq("mismatch");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("mismatch-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);

        bizFailCode(completeTask("2502", "default", Set.of(), task, null),
                WorkflowErrorCodes.WORKFLOW_ASSIGNEE_MISMATCH, "非 assignee complete 应拒绝");
        assertThat(taskStatus(task)).as("拒绝后任务状态不变").isEqualTo("CREATED");
        assertThat(instanceStatus(instanceId)).as("拒绝后实例不推进").isEqualTo("RUNNING");

        // 非候选 claim 同样拒绝（03-0003 注释：非 assignee complete / 非候选 claim）
        bizFailCode(taskOp("2502", "default", Set.of(), task, "claim", null),
                WorkflowErrorCodes.WORKFLOW_ASSIGNEE_MISMATCH, "非候选 claim 应拒绝");
    }

    @Test
    void should_reject_to_previous_user_task_when_reject_without_target() throws Exception {
        String key = uniq("reject-default");
        long defId = createAndDeploy(key, bpmn(key, REJECT_CHAIN_BODY));
        String bk = uniq("reject-inst");
        ok(startInstanceAs("2603", "default", Set.of(), defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);

        Long t1 = awaitOpenTask(instanceId, "t1", 6_000);
        ok(completeTask("2601", "default", Set.of(), t1, null), "一级完成应成功");
        Long t2 = awaitOpenTask(instanceId, "t2", 6_000);
        assertThat(t2).as("二级任务应创建").isNotNull();

        // 缺省驳回：回退到上一个已完成 UserTask（t1）——旧任务 REJECTED、新 t1 任务实例生成
        ObjectNode rejectBody = bodyNode();
        rejectBody.put("comment", "材料不足退回");
        ok(taskOp("2602", "default", Set.of(), t2, "reject", rejectBody), "缺省驳回应成功");
        assertThat(taskStatus(t2)).as("被驳回任务应 REJECTED").isEqualTo("REJECTED");
        Long t1Again = awaitOpenTask(instanceId, "t1", 6_000);
        assertThat(t1Again).as("缺省驳回应回到上一 UserTask（t1 新任务实例）").isNotNull();
        assertThat(t1Again).as("驳回产生新任务实例").isNotEqualTo(t1);
        assertThat(instanceStatus(instanceId)).as("驳回后实例仍运行").isEqualTo("RUNNING");
        assertThat(historyActions(instanceId)).as("驳回应记 REJECT_EDGE 历史边")
                .contains("REJECT_EDGE");
    }

    @Test
    void should_reject_to_specified_historical_node_when_target_node_id_given() throws Exception {
        String key = uniq("reject-target");
        long defId = createAndDeploy(key, bpmn(key, REJECT_CHAIN3_BODY));
        String bk = uniq("reject3-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);

        Long t1 = awaitOpenTask(instanceId, "t1", 6_000);
        ok(completeTask("2701", "default", Set.of(), t1, null), "一级完成应成功");
        Long t2 = awaitOpenTask(instanceId, "t2", 6_000);
        ok(completeTask("2702", "default", Set.of(), t2, null), "二级完成应成功");
        Long t3 = awaitOpenTask(instanceId, "t3", 6_000);

        // 指定回退：从 t3 直退到历史 UserTask t1
        ObjectNode rejectBody = bodyNode();
        rejectBody.put("targetNodeId", "t1");
        rejectBody.put("comment", "指定退回一级");
        ok(taskOp("2703", "default", Set.of(), t3, "reject", rejectBody), "指定回退应成功");
        assertThat(taskStatus(t3)).isEqualTo("REJECTED");
        Long t1Again = awaitOpenTask(instanceId, "t1", 6_000);
        assertThat(t1Again).as("指定回退应重新落 t1 任务").isNotNull();

        // 指定未知节点 → 校验失败整体拒绝
        ObjectNode badTarget = bodyNode();
        badTarget.put("targetNodeId", "ghost-target");
        bizFail(taskOp("2701", "default", Set.of(), t1Again, "reject", badTarget),
                "驳回未知目标节点应拒绝");
    }

    @Test
    void should_withdraw_running_instance_when_initiator_terminates() throws Exception {
        // 撤回=发起人 terminate：实例 TERMINATED + 未完成任务全 CANCELLED（驳回链第三型）
        String key = uniq("withdraw");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("withdraw-inst");
        ok(startInstanceAs("3301", "default", Set.of(), defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);

        ok(instanceOp("3301", "default", Set.of(), instanceId, "terminate", null),
                "发起人撤回应成功");
        assertThat(instanceStatus(instanceId)).as("撤回后实例 TERMINATED").isEqualTo("TERMINATED");
        assertThat(taskStatus(task)).as("未完成任务应 CANCELLED").isEqualTo("CANCELLED");
        // TL 裁决⑨a：撤回（terminate）后任务操作由实例域阻断语义承载（02-0002 实例域）
        bizFailCode(completeTask("2501", "default", Set.of(), task, null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "撤回后任务操作应拒绝（实例域）");
    }

    @Test
    void should_transfer_task_to_new_assignee_when_transfer_requested() throws Exception {
        String key = uniq("transfer");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("transfer-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);
        ok(taskOp("2501", "default", Set.of(), task, "claim", null), "认领应成功");

        // 转办：原 assignee 转给新 assignee → 新人可办、旧人失效
        ObjectNode transferBody = bodyNode();
        transferBody.put("assignee", "2503");
        ok(taskOp("2501", "default", Set.of(), task, "transfer", transferBody), "转办应成功");
        assertThat(taskAssignee(task)).as("转办后 assignee 更新").isEqualTo("2503");
        bizFailCode(completeTask("2501", "default", Set.of(), task, null),
                WorkflowErrorCodes.WORKFLOW_ASSIGNEE_MISMATCH, "原 assignee 转办后应失效");
        ok(completeTask("2503", "default", Set.of(), task, null), "新 assignee 完成应成功");
    }

    @Test
    void should_delegate_once_and_refuse_second_level_when_delegate_chain_exceeded() throws Exception {
        // 委托一期=转办+owner 记录、委托链单层（确认⑤）：二层 delegate → DELEGATE_STATE_INVALID
        String key = uniq("delegate");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("delegate-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);

        ObjectNode first = bodyNode();
        first.put("assignee", "2504");
        ok(taskOp("2501", "default", Set.of(), task, "delegate", first), "一次委托应成功");
        assertThat(taskAssignee(task)).as("委托后 assignee 更新").isEqualTo("2504");

        ObjectNode second = bodyNode();
        second.put("assignee", "2505");
        // 单层委托链用尽（被委托人再委托）：wire 码宽容 03-0004（api 注释钉死「已用尽再委托」）
        // 或 03-0003（实现若按「仅 owner 可再委托」归归属不匹配——形态归 TL 裁决），拒绝语义不变
        JsonNode delegateeReDelegate = taskOp("2504", "default", Set.of(), task, "delegate", second);
        bizFail(delegateeReDelegate, "被委托人再委托应拒绝（单层链）");
        assertThat(delegateeReDelegate.path("code").asText())
                .as("被委托人再委托 wire 码应为 DELEGATE_STATE_INVALID 或 ASSIGNEE_MISMATCH")
                .isIn(WorkflowErrorCodes.WORKFLOW_DELEGATE_STATE_INVALID,
                        WorkflowErrorCodes.WORKFLOW_ASSIGNEE_MISMATCH);
        // 已委托任务由 owner 再委托 → DELEGATE_STATE_INVALID（api 03-0004 注释钉死）
        bizFailCode(taskOp("2501", "default", Set.of(), task, "delegate", second),
                WorkflowErrorCodes.WORKFLOW_DELEGATE_STATE_INVALID, "对已委托任务再委托应拒绝");
        assertThat(taskAssignee(task)).as("非法委托后 assignee 不变").isEqualTo("2504");

        // 被委托人可完成；实例到终点
        ok(completeTask("2504", "default", Set.of(), task, null), "被委托人完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("委托完成后实例到终点").isTrue();
    }

    @Test
    void should_reject_unclaim_and_double_claim_when_task_state_invalid() throws Exception {
        String key = uniq("task-state");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("task-state-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);

        // 未认领 unclaim → STATE_CONFLICT
        bizFailCode(taskOp("2501", "default", Set.of(), task, "unclaim", null),
                WorkflowErrorCodes.WORKFLOW_TASK_STATE_CONFLICT, "未认领 unclaim 应拒绝");
        // 已认领再 claim（实例 RUNNING）→ 03-0002 任务域锚；认领态直接完成（unclaim 复位后的
        // 固定指派完成语义契约未钉，不在此钉）
        ok(taskOp("2501", "default", Set.of(), task, "claim", null), "认领应成功");
        bizFailCode(taskOp("2501", "default", Set.of(), task, "claim", null),
                WorkflowErrorCodes.WORKFLOW_TASK_STATE_CONFLICT, "已认领再 claim 应拒绝");
        // 完成后（实例 COMPLETED）再操作 → 实例域阻断承载（TL 裁决⑨a 同语义：终态实例的任务
        // 操作由 INSTANCE_STATE_CONFLICT 02-0002 优先阻断；03-0002 任务域锚已由前两腿承载）
        ok(completeTask("2501", "default", Set.of(), task, null), "完成应成功");
        bizFailCode(completeTask("2501", "default", Set.of(), task, null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "终态实例任务再 complete 应拒绝");
        bizFailCode(taskOp("2501", "default", Set.of(), task, "claim", null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "终态实例 claim 应拒绝");
        bizFailCode(taskOp("2501", "default", Set.of(), task, "transfer",
                        bodyNode().put("assignee", "2599")),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "终态实例 transfer 应拒绝");
        bizFailCode(taskOp("2501", "default", Set.of(), task, "delegate",
                        bodyNode().put("assignee", "2599")),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "终态实例 delegate 应拒绝");
    }

    // ---------- AC-7：并发 claim/complete 竞争收敛单胜 ----------

    /** 双线程竞争执行器（latch 对齐起跑）。 */
    private JsonNode[] race(Callable<JsonNode> left, Callable<JsonNode> right) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> lf = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return left.call();
            });
            Future<JsonNode> rf = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return right.call();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("竞争线程应就绪").isTrue();
            go.countDown();
            return new JsonNode[]{lf.get(30, TimeUnit.SECONDS), rf.get(30, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    private long successCount(JsonNode... responses) {
        long count = 0;
        for (JsonNode response : responses) {
            if (response != null && response.path("success").asBoolean()) {
                count++;
            }
        }
        return count;
    }

    @Test
    void should_converge_single_winner_when_two_threads_claim_concurrently() throws Exception {
        String key = uniq("claim-race");
        long defId = createAndDeploy(key, bpmn(key, CANDIDATE_BODY.formatted("race-qa")));
        String bk = uniq("claim-race-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-cand", 6_000);

        JsonNode[] outcomes = race(
                () -> taskOp("2201", "default", Set.of("race-qa"), task, "claim", null),
                () -> taskOp("2202", "default", Set.of("race-qa"), task, "claim", null));
        assertThat(successCount(outcomes))
                .as("并发 claim 应收敛单胜（失败方应为信封失败）：%s / %s",
                        outcomes[0], outcomes[1]).isEqualTo(1);
        String winner = outcomes[0].path("success").asBoolean() ? "2201" : "2202";
        assertThat(taskStatus(task)).as("收敛后 CLAIMED").isEqualTo("CLAIMED");
        assertThat(taskAssignee(task)).as("胜者成为 assignee").isEqualTo(winner);

        // 胜者可完成到终点（一致终态）
        ok(completeTask(winner, "default", Set.of("race-qa"), task, null), "胜者完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("竞争实例到终点").isTrue();
    }

    @Test
    void should_converge_single_winner_when_two_threads_complete_concurrently() throws Exception {
        // 三轮迭代（不同实例）：每轮恰一个成功、任务单完成、实例单推进、无残留开放任务
        String key = uniq("complete-race");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        for (int round = 0; round < 3; round++) {
            String bk = uniq("complete-race-r" + round);
            ok(startInstance(defId, bk, null), "第" + round + "轮启动应成功");
            long instanceId = findInstanceId(bk);
            Long task = awaitOpenTask(instanceId, "task-only", 6_000);
            assertThat(task).isNotNull();

            JsonNode[] outcomes = race(
                    () -> completeTask("2501", "default", Set.of(), task, null),
                    () -> completeTask("2501", "default", Set.of(), task, null));
            assertThat(successCount(outcomes))
                    .as("第 %d 轮并发 complete 应收敛单胜：%s / %s",
                            round, outcomes[0], outcomes[1]).isEqualTo(1);
            assertThat(await(6_000, () -> "COMPLETED".equals(taskStatus(task))))
                    .as("第 %d 轮任务应 COMPLETED", round).isTrue();
            assertThat(historyCount(instanceId, "LEAVE"))
                    .as("第 %d 轮任务节点 LEAVE 历史不得缺失", round).isGreaterThanOrEqualTo(1);
            assertThat(await(6_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                    .as("第 %d 轮实例应完成", round).isTrue();
            assertThat(findOpenTaskId(instanceId, "task-only"))
                    .as("第 %d 轮不得残留开放任务", round).isNull();
        }
    }

    // ---------- AC-11：实例状态机矩阵（suspend/resume/terminate） ----------

    @Test
    void should_suspend_and_resume_instance_when_admin_operates() throws Exception {
        String key = uniq("suspend");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("suspend-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);

        // ① suspend → SUSPENDED（挂起原因承载，缺省 ADMIN_SUSPEND）
        ok(instanceOp("1", "default", Set.of("super_admin"), instanceId, "suspend", null),
                "挂起应成功");
        assertThat(instanceStatus(instanceId)).isEqualTo("SUSPENDED");
        assertThat(instanceSuspendReason(instanceId)).as("挂起原因应承载").isNotBlank();

        // ② SUSPENDED 态受理任务操作 → INSTANCE_STATE_CONFLICT（02-0002 注释钉死）
        bizFailCode(completeTask("2501", "default", Set.of(), task, null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "挂起态 complete 应拒绝");
        bizFailCode(taskOp("2501", "default", Set.of(), task, "claim", null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "挂起态 claim 应拒绝");
        // 重复挂起 → STATE_CONFLICT
        bizFailCode(instanceOp("1", "default", Set.of("super_admin"), instanceId, "suspend", null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "重复挂起应拒绝");

        // ③ resume → RUNNING，任务操作恢复
        ok(instanceOp("1", "default", Set.of("super_admin"), instanceId, "resume", null),
                "恢复应成功");
        assertThat(instanceStatus(instanceId)).isEqualTo("RUNNING");
        // 对运行态 resume → STATE_CONFLICT（02-0002 注释：resume 对非挂起实例）
        bizFailCode(instanceOp("1", "default", Set.of("super_admin"), instanceId, "resume", null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "运行态 resume 应拒绝");
        ok(completeTask("2501", "default", Set.of(), task, null), "恢复后完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("恢复后实例到终点").isTrue();

        // ④ 终态实例 suspend/resume → STATE_CONFLICT
        bizFailCode(instanceOp("1", "default", Set.of("super_admin"), instanceId, "suspend", null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "终态 suspend 应拒绝");
        bizFailCode(instanceOp("1", "default", Set.of("super_admin"), instanceId, "resume", null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "终态 resume 应拒绝");
    }

    @Test
    void should_cancel_all_unfinished_and_terminate_when_instance_terminated() throws Exception {
        // 并行模型终止：未完成 execution/task 全 CANCELLED（确认⑤撤回语义全集）
        String key = uniq("terminate");
        long defId = createAndDeploy(key, bpmn(key, PARALLEL_BODY));
        String bk = uniq("terminate-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        assertThat(await(6_000, () -> findOpenTaskId(instanceId, "task-a") != null
                && findOpenTaskId(instanceId, "task-b") != null
                && findOpenTaskId(instanceId, "task-c") != null))
                .as("三支任务应齐备").isTrue();

        ok(instanceOp("1", "default", Set.of("super_admin"), instanceId, "terminate", null),
                "终止应成功");
        assertThat(instanceStatus(instanceId)).as("终止后 TERMINATED").isEqualTo("TERMINATED");
        for (String node : List.of("task-a", "task-b", "task-c")) {
            Long open = findOpenTaskId(instanceId, node);
            assertThat(open).as("终止后 %s 不得残留开放任务", node).isNull();
        }
        Long cancelled = jdbcTemplate.queryForObject(
                "select count(*) from wf_task where instance_id = ? and status = 'CANCELLED'",
                Long.class, instanceId);
        assertThat(cancelled).as("未完成任务应全 CANCELLED").isEqualTo(3L);
        // 终态再操作 → STATE_CONFLICT（02-0002）
        bizFailCode(instanceOp("1", "default", Set.of("super_admin"), instanceId, "terminate", null),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT, "终态再 terminate 应拒绝");
    }

    // ---------- AC-8：ServiceTask HTTP 委托（下发协议体 / 回调推进 / 重试耗尽挂起） ----------

    @Test
    void should_dispatch_delegation_and_advance_when_service_callback_succeeds() throws Exception {
        String key = uniq("svc-ok");
        long defId = createAndDeploy(key, bpmn(key, SERVICE_BODY));
        String bk = uniq("svc-ok-inst");
        ObjectNode variables = bodyNode();
        variables.put("svcVar", "svc-marker-8");
        ok(startInstance(defId, bk, variables), "ServiceTask 模型启动应成功");
        long instanceId = findInstanceId(bk);

        // ① 委托下发到桩（协议体断言：executionId/变量快照/traceId）
        RecordedRequest dispatch = awaitDelegation(1, 8_000);
        assertThat(dispatch).as("到达 ServiceTask 应下发 HTTP 委托").isNotNull();
        assertDispatchPayload(dispatch.body(), bk, "svc");
        assertThat(dispatch.body()).as("下发体应携变量快照").contains("svc-marker-8");
        String executionId = executionIdOfDispatch(dispatch);
        List<String> execNodes = jdbcTemplate.queryForList(
                "select node_id from wf_execution where id = ?", String.class,
                Long.parseLong(executionId));
        assertThat(execNodes).as("下发体 executionId 应对应 svc 节点 execution")
                .isNotEmpty().first().isEqualTo("svc");

        // ② 回调成功 → 推进到 task-after
        ok(serviceCallback(executionId, true, null), "委托成功回调应受理");
        Long afterTask = awaitOpenTask(instanceId, "task-after", 8_000);
        assertThat(afterTask).as("回调成功应推进到委托后任务").isNotNull();

        // ③ 完成到终点
        ok(completeTask("3001", "default", Set.of(), afterTask, null), "委托后任务完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("委托模型到终点").isTrue();

        // ④ 未知 executionId 回调拒绝（internal 面守卫）
        bizFail(serviceCallback("999999999", true, null), "未知 executionId 回调应拒绝");
    }

    @Test
    void should_suspend_instance_when_service_task_callbacks_fail_until_retry_exhausted()
            throws Exception {
        // A-9：重试=重新下发（payload.attempt 承载尝试序号），每次下发以失败回调应答直至耗尽
        String key = uniq("svc-fail");
        long defId = createAndDeploy(key, bpmn(key, SERVICE_BODY));
        String bk = uniq("svc-fail-inst");
        ok(startInstance(defId, bk, null), "ServiceTask 失败链启动应成功");
        long instanceId = findInstanceId(bk);

        int handled = 0;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
                && !"SUSPENDED".equals(instanceStatus(instanceId))) {
            List<RecordedRequest> dispatches = delegationDispatches();
            while (handled < dispatches.size() && handled < 6) {
                String executionId = executionIdOfDispatch(dispatches.get(handled));
                JsonNode cbResp = serviceCallback(executionId, false, "biz-down-142316");
                handled++;
                if (!cbResp.path("success").asBoolean()) {
                    // 回调被拒（同 execution 重复失败计数语义）：消耗该下发，等待重发
                    break;
                }
            }
            sleepQuietly(100);
        }

        // ① 重试确有再尝试（下发次数≥2）且 payload.attempt 递增
        List<RecordedRequest> dispatches = delegationDispatches();
        assertThat(dispatches.size())
                .as("失败重试应再次下发委托，实际下发=%d", dispatches.size())
                .isGreaterThanOrEqualTo(2);
        int maxAttempt = 0;
        for (RecordedRequest request : dispatches) {
            maxAttempt = Math.max(maxAttempt, JSON.readTree(request.body()).path("attempt").asInt());
        }
        assertThat(maxAttempt).as("重试下发 attempt 应递增").isGreaterThanOrEqualTo(1);

        // ② 耗尽 → 实例 SUSPENDED（reason=SERVICE_TASK_FAILED）
        assertThat(instanceStatus(instanceId))
                .as("重试耗尽应挂起实例，实际=%s", instanceStatus(instanceId))
                .isEqualTo("SUSPENDED");
        assertThat(instanceSuspendReason(instanceId))
                .as("挂起原因应含 SERVICE_TASK_FAILED，实际=%s", instanceSuspendReason(instanceId))
                .contains("SERVICE_TASK_FAILED");
        assertThat(taskCount(instanceId, "task-after", "'CREATED','CLAIMED'"))
                .as("挂起不得推进到委托后任务").isZero();
    }

    // ---------- AC-9：定时边界事件（注册协议 / fire 中断 / 重复 fire 拒绝 / 先完成注销） ----------

    @Test
    void should_fire_boundary_timer_and_interrupt_task_when_scheduler_fires() throws Exception {
        String key = uniq("timer-fire");
        long defId = createAndDeploy(key, bpmn(key, TIMER_BODY));
        String bk = uniq("timer-fire-inst");
        long startEpoch = System.currentTimeMillis() / 1000;
        ok(startInstance(defId, bk, null), "定时边界模型启动应成功");
        long instanceId = findInstanceId(bk);
        Long mainTask = awaitOpenTask(instanceId, "task-main", 6_000);
        assertThat(mainTask).as("主任务应创建").isNotNull();

        // ① timer 落库 PENDING + due_at≈start+5m（timeDuration PT5M，A-4）+ uk jobKey
        Map<String, Object> timer = awaitCondition(6_000, () -> timerRow(instanceId));
        assertThat(timer).as("边界事件应落 wf_timer_job").isNotNull();
        assertThat(timer.get("status")).as("timer 初始 PENDING").isEqualTo("PENDING");
        double dueEpoch = ((Number) timer.get("due_epoch")).doubleValue();
        assertThat(dueEpoch)
                .as("due_at 应≈start+5m（实际偏移 %.0fs）", dueEpoch - startEpoch)
                .isBetween(startEpoch + 240.0, startEpoch + 360.0);
        long timerId = ((Number) timer.get("id")).longValue();
        assertThat(String.valueOf(timer.get("scheduler_job_key")))
                .as("scheduler_job_key 应为 wf-timer:{timerJobId} 协议")
                .isEqualTo("wf-timer:" + timerId);

        // ② 调度中心注册协议（jobKey=wf-timer:*、appName、onceFireAt、handler/param 在场）
        RecordedRequest registration = awaitSchedulerRegister(8_000);
        assertThat(registration).as("应向调度中心注册一次性任务（A-2 解析面）").isNotNull();
        JsonNode registerBody = JSON.readTree(registration.body());
        assertThat(registerBody.path("jobKey").asText())
                .as("注册体 jobKey=wf-timer:{timerJobId}").isEqualTo("wf-timer:" + timerId);
        assertThat(registerBody.path("appName").asText())
                .as("注册体 appName=platform-workflow").isEqualTo("platform-workflow");
        assertThat(registerBody.path("onceFireAt").asText())
                .as("注册体 onceFireAt 在场").isNotBlank();
        assertThat(registerBody.path("handler").asText())
                .as("注册体 handler 在场").isNotBlank();
        assertThat(registerBody.has("param")).as("注册体 param 在场").isTrue();

        // ③ fire 回调 → timer FIRED + 主任务 CANCELLED + 边界分支任务（中断型）
        ok(fireTimer(timerId), "fire 回调应受理");
        assertThat(await(6_000, () -> "FIRED".equals(timerRow(instanceId).get("status"))))
                .as("fire 后 timer 应 FIRED").isTrue();
        assertThat(taskStatus(mainTask)).as("中断当前任务应 CANCELLED").isEqualTo("CANCELLED");
        Long boundaryTask = awaitOpenTask(instanceId, "task-boundary", 8_000);
        assertThat(boundaryTask).as("fire 应转入边界流出分支").isNotNull();
        assertThat(instanceStatus(instanceId)).as("边界中断后实例仍运行").isEqualTo("RUNNING");

        // ④ 重复 fire → 拒绝（CAS 幂等：PENDING→FIRED 才生效）
        bizFail(fireTimer(timerId), "重复 fire 应拒绝");
        assertThat(timerRow(instanceId).get("status")).as("重复 fire 后仍 FIRED").isEqualTo("FIRED");
        long boundaryTaskCount = jdbcTemplate.queryForObject(
                "select count(*) from wf_task where instance_id = ? and node_id = 'task-boundary'",
                Long.class, instanceId);
        assertThat(boundaryTaskCount).as("重复 fire 不得二次中断产生第二任务").isEqualTo(1L);

        // ⑤ 完成边界分支 → 终点
        ok(completeTask("2902", "default", Set.of(), boundaryTask, null), "边界任务完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("边界分支到终点").isTrue();
    }

    @Test
    void should_cancel_timer_and_deregister_when_task_completes_before_due() throws Exception {
        String key = uniq("timer-cancel");
        long defId = createAndDeploy(key, bpmn(key, TIMER_BODY));
        String bk = uniq("timer-cancel-inst");
        ok(startInstance(defId, bk, null), "定时边界模型启动应成功");
        long instanceId = findInstanceId(bk);
        Long mainTask = awaitOpenTask(instanceId, "task-main", 6_000);
        Map<String, Object> timer = awaitCondition(6_000, () -> timerRow(instanceId));
        assertThat(timer).as("timer 行应落库").isNotNull();
        long timerId = ((Number) timer.get("id")).longValue();
        assertThat(awaitSchedulerRegister(8_000))
                .as("注册请求应先于完成路径发生（A-2 解析面）").isNotNull();

        // ① 主任务先完成 → timer CANCELLED + 注销调度中心 job（DELETE wf-timer:{id}）
        ok(completeTask("2901", "default", Set.of(), mainTask, null), "主任务完成应成功");
        assertThat(await(8_000, () -> "CANCELLED".equals(timerRow(instanceId).get("status"))))
                .as("任务完成后 timer 应 CANCELLED，实际=%s", timerRow(instanceId)).isTrue();
        assertThat(awaitSchedulerDeregister("wf-timer:" + timerId, 8_000))
                .as("应向调度中心注销 job（DELETE /internal/v1/scheduler/jobs/{jobKey}）").isNotNull();

        // ② 注销后 fire → 拒绝（非 PENDING 不生效）；不得产生边界任务
        bizFail(fireTimer(timerId), "CANCELLED 后 fire 应拒绝");
        Long boundaryTask = awaitOpenTask(instanceId, "task-boundary", 3_000);
        assertThat(boundaryTask).as("注销后 fire 不得产生边界任务").isNull();
        assertThat(instanceStatus(instanceId)).as("实例已随主路径完成").isEqualTo("COMPLETED");
    }

    // ---------- AC-10：子流程（读穿透 / 遮蔽写不穿透 / 独立 scope / 子树回收） ----------

    @Test
    void should_shadow_variables_and_recycle_subprocess_scope_when_subprocess_completes()
            throws Exception {
        String key = uniq("subproc");
        long defId = createAndDeploy(key, bpmn(key, SUBPROCESS_BODY));
        String bk = uniq("subproc-inst");
        ok(startInstance(defId, bk, vars("amount", 1)), "子流程启动应成功");
        long instanceId = findInstanceId(bk);

        // ① 读穿透：子流程网关读到父变量 amount=1 → sub-low（<10 分支）
        Long subLow = awaitOpenTask(instanceId, "sub-low", 8_000);
        assertThat(subLow).as("子流程网关应读穿透父变量（amount=1 → sub-low）").isNotNull();

        // ② 独立 scope（契约钉死列语义）：子流程活跃 execution 存在，且子流程域入口 execution
        //    以 is_scope_root=true 标注（确认② is_scope_root 列 + §3.2 scope 子树；实现形态上
        //    外层 token 允许随行进入子域——外层驻留计数不作断言）
        long innerActive = activeExecutionCount(instanceId, true,
                "sub-start", "sub-gw1", "sub-low", "sub-high", "sub-gw2", "sub-big", "sub-small", "sub-end");
        assertThat(innerActive).as("子流程子树应有活跃 execution").isGreaterThanOrEqualTo(1);
        Long scopeRootCount = jdbcTemplate.queryForObject(
                "select count(*) from wf_execution where instance_id = ? and is_scope_root and node_id in "
                        + "('sub-start','sub-gw1','sub-low','sub-high','sub-gw2','sub-big','sub-small','sub-end')",
                Long.class, instanceId);
        assertThat(scopeRootCount)
                .as("子流程域入口 execution 应标 is_scope_root=true（独立 scope，实际=%d）", scopeRootCount)
                .isGreaterThanOrEqualTo(1L);

        // ③ 遮蔽写：sub-low 表单携 {amount: 99}——若写仅落子 scope，二级网关读 99 → sub-big；
        //    若表单不写变量 → 读父 1 → sub-small；两形态均合法（A-3，表单→变量契约未钉）。
        //    红线断言：父级实例变量 amount 不得被穿透改写（写不穿透）。
        ObjectNode form = bodyNode();
        form.put("amount", 99);
        ok(completeTask("3101", "default", Set.of(), subLow, form), "子任务表单完成应成功");
        Long bigOrSmall = awaitCondition(8_000, () -> {
            Long big = findOpenTaskId(instanceId, "sub-big");
            return big != null ? big : findOpenTaskId(instanceId, "sub-small");
        });
        assertThat(bigOrSmall).as("二级网关应落分支任务").isNotNull();
        ok(completeTask("3101", "default", Set.of(), bigOrSmall, null), "二级任务办结应成功");

        // ④ 子流程收尾 → 尾任务；实例级变量 amount 保持 1（写不穿透——若被改 99 即违例）
        Long tail = awaitOpenTask(instanceId, "tail", 8_000);
        assertThat(tail).as("子流程结束应回到父流程尾任务").isNotNull();
        String parentAmount = variableText(instanceId, "amount");
        assertThat(parentAmount).as("父级实例变量应保持原值（写不穿透），实际=%s", parentAmount)
                .isNotNull();
        JsonNode amountJson = JSON.readTree(parentAmount);
        long parentAmountValue = amountJson.isNumber() ? amountJson.asLong()
                : Long.parseLong(amountJson.asText());
        assertThat(parentAmountValue).as("父级 amount 不得被子流程写穿透").isEqualTo(1L);

        // ⑤ 结束回收子树：尾任务期子流程内不再有活跃 execution
        long innerAfter = activeExecutionCount(instanceId, true,
                "sub-start", "sub-gw1", "sub-low", "sub-high", "sub-gw2", "sub-big", "sub-small", "sub-end");
        assertThat(innerAfter).as("子流程结束应回收子树 execution").isZero();

        ok(completeTask("3102", "default", Set.of(), tail, null), "尾任务完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("子流程模型到终点").isTrue();
    }

    // ---------- AC-12：命令回滚整体性（库内状态与异常一致） ----------

    @Test
    void should_rollback_without_partial_advance_when_command_validation_fails() throws Exception {
        String key = uniq("rollback");
        long defId = createAndDeploy(key, bpmn(key, REJECT_CHAIN_BODY));
        String bk = uniq("rollback-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long t1 = awaitOpenTask(instanceId, "t1", 6_000);
        ok(completeTask("2601", "default", Set.of(), t1, null), "一级完成应成功");
        Long t2 = awaitOpenTask(instanceId, "t2", 6_000);
        ok(taskOp("2602", "default", Set.of(), t2, "claim", null), "认领应成功");

        long historyBefore = historyTotalCount(instanceId);
        long tasksBefore = jdbcTemplate.queryForObject(
                "select count(*) from wf_task where instance_id = ?", Long.class, instanceId);

        // 驳回未知目标 → 校验失败整体拒绝：任务保持 CLAIMED、无新任务、历史不新增、实例不推进
        ObjectNode badTarget = bodyNode();
        badTarget.put("targetNodeId", "ghost-rollback");
        badTarget.put("comment", "非法回退");
        bizFail(taskOp("2602", "default", Set.of(), t2, "reject", badTarget),
                "驳回未知目标应整体拒绝");
        assertThat(taskStatus(t2)).as("回滚后任务保持 CLAIMED").isEqualTo("CLAIMED");
        assertThat(taskAssignee(t2)).as("回滚后 assignee 不变").isEqualTo("2602");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_task where instance_id = ?", Long.class, instanceId))
                .as("回滚后不得新增任务行").isEqualTo(tasksBefore);
        assertThat(historyTotalCount(instanceId))
                .as("回滚后历史不得新增（EventPublish AFTER_COMMIT 亦无副作用）")
                .isEqualTo(historyBefore);
        assertThat(instanceStatus(instanceId)).as("实例不推进").isEqualTo("RUNNING");
    }

    @Test
    void should_converge_or_rollback_cleanly_when_optimistic_lock_version_conflicts()
            throws Exception {
        // AC-12 锁冲突耗尽腿：夹具直改 execution.version 制造持续冲突——
        // 形态一（重试重读收敛）：complete 成功且终态一致；
        // 形态二（重试耗尽失败）：命令失败且无部分推进（任务保持/历史不变/实例不推进）。
        String key = uniq("optimistic");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk = uniq("optimistic-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long task = awaitOpenTask(instanceId, "task-only", 6_000);
        ok(taskOp("2501", "default", Set.of(), task, "claim", null), "认领应成功");

        long historyBefore = historyTotalCount(instanceId);
        jdbcTemplate.update(
                "update wf_execution set version = version + 1000 where instance_id = ?", instanceId);

        JsonNode completeResp = completeTask("2501", "default", Set.of(), task, null);
        if (completeResp.path("success").asBoolean()) {
            // 形态一：乐观锁重试收敛（重读后推进），终态必须完整一致
            assertThat(await(6_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                    .as("收敛完成应推进实例，实际=%s", instanceStatus(instanceId)).isTrue();
            assertThat(historyCount(instanceId, "LEAVE"))
                    .as("收敛完成任务节点 LEAVE 恰在场").isGreaterThanOrEqualTo(1);
        } else {
            // 形态二：重试（≤3，50/100/200ms）耗尽失败 → 整体回滚无部分推进
            assertThat(completeResp.path("code").asText()).as("耗尽失败 code≠0").isNotEqualTo("0");
            assertThat(taskStatus(task)).as("耗尽失败后任务保持 CLAIMED").isEqualTo("CLAIMED");
            assertThat(historyTotalCount(instanceId))
                    .as("耗尽失败历史不得新增").isEqualTo(historyBefore);
            assertThat(instanceStatus(instanceId)).as("耗尽失败实例不推进").isEqualTo("RUNNING");
            Long open = findOpenTaskId(instanceId, "task-only");
            assertThat(open).as("耗尽失败不得残留终态错位").isEqualTo(task);
        }
    }

    // ---------- AC-13：编译缓存失效（disable / 新版本部署 / 运行实例不受影响） ----------

    @Test
    void should_invalidate_compiled_model_and_serve_new_version_when_disabled_or_redeployed()
            throws Exception {
        String key = uniq("cache");
        long defV1 = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        String bk1 = uniq("cache-inst-v1");
        ok(startInstance(defV1, bk1, null), "v1 实例应启动（编译缓存生成）");
        long inst1 = findInstanceId(bk1);
        Long taskV1 = awaitOpenTask(inst1, "task-only", 6_000);
        assertThat(taskV1).isNotNull();

        // ① disable → 新实例拒绝（守卫承载，见 AC-2 生命周期）；运行实例不受影响
        ok(callAsSuperAdmin(post(DEFS_URL + "/" + defV1 + "/disable")), "v1 禁用应成功");
        ok(completeTask("2501", "default", Set.of(), taskV1, null), "v1 运行实例不受影响");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(inst1))))
                .as("v1 运行实例照常完成").isTrue();

        // ② 新版本部署（A-6 二形态宽容）：version+1 的新定义可启动（旧版本缓存不得串版本）
        JsonNode redeploy = callAsSuperAdmin(post(DEFS_URL + "/" + defV1 + "/deploy"));
        if (!redeploy.path("success").asBoolean()) {
            String xmlV2 = bpmn(key, SINGLE_TASK_BODY.replace("唯一任务", "唯一任务V2"));
            saveXml(defV1, xmlV2);
            deploy(defV1);
        }
        Long latestId = findLatestDefinitionId(key);
        assertThat(latestId).isNotNull();
        int latestVersion = jdbcTemplate.queryForObject(
                "select deploy_version from wf_process_definition where id = ?", Integer.class, latestId);
        assertThat(latestVersion)
                .as("同 key 再部署应产生新版本（A-6），实际最新 deploy_version=%d", latestVersion)
                .isGreaterThanOrEqualTo(2);
        long defV2 = latestId;

        // ③ 新版本实例照常推进（新版本编译模型生效）
        String bk2 = uniq("cache-inst-v2");
        ok(startInstance(defV2, bk2, null), "新版本实例应启动");
        long inst2 = findInstanceId(bk2);
        Long taskV2 = awaitOpenTask(inst2, "task-only", 6_000);
        assertThat(taskV2).as("新版本模型应可用").isNotNull();
        ok(completeTask("2501", "default", Set.of(), taskV2, null), "新版本任务完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(inst2))))
                .as("新版本实例到终点").isTrue();

        // ④ 运行期查询零解析为间接断言（缓存行为经 disable/新版本承载；直接解析观测属实现白盒，
        //    不在 HTTP 面——手段如实上报）
    }

    // ---------- AC-15：历史动作链（ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE 按序完整） ----------

    @Test
    void should_record_ordered_history_chain_with_reject_edge_when_task_rejected() throws Exception {
        String key = uniq("history-reject");
        long defId = createAndDeploy(key, bpmn(key, REJECT_CHAIN_BODY));
        String bk = uniq("history-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        Long t1 = awaitOpenTask(instanceId, "t1", 6_000);
        ok(completeTask("2601", "default", Set.of(), t1, null), "一级完成应成功");
        Long t2 = awaitOpenTask(instanceId, "t2", 6_000);
        ok(taskOp("2602", "default", Set.of(), t2, "reject",
                bodyNode().put("comment", "退回")), "缺省驳回应成功");
        Long t1Again = awaitOpenTask(instanceId, "t1", 6_000);
        ok(completeTask("2601", "default", Set.of(), t1Again, null), "二级轮完成应成功");
        Long t2Again = awaitOpenTask(instanceId, "t2", 6_000);
        ok(completeTask("2602", "default", Set.of(), t2Again, null), "终轮完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("历史场景实例应完成").isTrue();

        // 历史链完整且按序（异步落库不丢——轮询至期望形态齐备再断言）
        assertThat(await(8_000, () -> historyCount(instanceId, "REJECT_EDGE") >= 1))
                .as("REJECT_EDGE 应落库").isTrue();
        List<String> actions = historyActions(instanceId);
        assertThat(actions).as("动作域齐备（ENTER/LEAVE/REJECT_EDGE）")
                .contains("ENTER", "LEAVE", "REJECT_EDGE");
        List<Map<String, Object>> ordered = jdbcTemplate.queryForList(
                "select node_id, action from wf_history_activity where instance_id = ? order by id",
                instanceId);
        int t1FirstEnter = indexOfAction(ordered, "t1", "ENTER", 0);
        int t1FirstLeave = indexOfAction(ordered, "t1", "LEAVE", t1FirstEnter + 1);
        assertThat(t1FirstEnter).as("t1 ENTER 应在场").isGreaterThanOrEqualTo(0);
        assertThat(t1FirstLeave).as("t1 ENTER 应先于 LEAVE").isGreaterThan(t1FirstEnter);
        int t2Enter = indexOfAction(ordered, "t2", "ENTER", 0);
        assertThat(t2Enter).as("t2 ENTER 应在场").isGreaterThanOrEqualTo(0);
        int rejectEdge = firstIndexOfAction(ordered, "REJECT_EDGE");
        assertThat(rejectEdge).as("REJECT_EDGE 应在场").isGreaterThanOrEqualTo(0);
        assertThat(countAction(ordered, "t1", "ENTER"))
                .as("驳回后 t1 应再次 ENTER（历史链保留）").isGreaterThanOrEqualTo(2);
    }

    @Test
    void should_record_timer_fire_history_when_boundary_timer_fired() throws Exception {
        String key = uniq("history-timer");
        long defId = createAndDeploy(key, bpmn(key, TIMER_BODY));
        String bk = uniq("history-timer-inst");
        ok(startInstance(defId, bk, null), "启动应成功");
        long instanceId = findInstanceId(bk);
        awaitOpenTask(instanceId, "task-main", 6_000);
        Map<String, Object> timer = awaitCondition(6_000, () -> timerRow(instanceId));
        long timerId = ((Number) timer.get("id")).longValue();

        ok(fireTimer(timerId), "fire 应受理");
        Long boundaryTask = awaitOpenTask(instanceId, "task-boundary", 8_000);
        assertThat(boundaryTask).isNotNull();
        ok(completeTask("2902", "default", Set.of(), boundaryTask, null), "边界任务完成应成功");
        assertThat(await(5_000, () -> "COMPLETED".equals(instanceStatus(instanceId))))
                .as("timer 场景实例应完成").isTrue();

        // 动作域含 TIMER_FIRE 且时序在 task-main ENTER 之后、task-boundary ENTER 之前
        assertThat(await(8_000, () -> historyActions(instanceId).contains("TIMER_FIRE")))
                .as("TIMER_FIRE 应落库").isTrue();
        List<Map<String, Object>> ordered = jdbcTemplate.queryForList(
                "select node_id, action from wf_history_activity where instance_id = ? order by id",
                instanceId);
        int mainEnter = indexOfAction(ordered, "task-main", "ENTER", 0);
        int timerFire = firstIndexOfAction(ordered, "TIMER_FIRE");
        int boundaryEnter = indexOfAction(ordered, "task-boundary", "ENTER", 0);
        assertThat(mainEnter).as("task-main ENTER 应在场").isGreaterThanOrEqualTo(0);
        assertThat(timerFire).as("TIMER_FIRE 应在场").isGreaterThanOrEqualTo(0);
        assertThat(boundaryEnter).as("task-boundary ENTER 应在场").isGreaterThanOrEqualTo(0);
        assertThat(timerFire).as("TIMER_FIRE 时序应在主任务 ENTER 之后").isGreaterThan(mainEnter);
        assertThat(boundaryEnter).as("边界任务 ENTER 应在 TIMER_FIRE 之后").isGreaterThan(timerFire);
    }

    private int indexOfAction(List<Map<String, Object>> ordered, String nodeId, String action, int from) {
        for (int i = from; i < ordered.size(); i++) {
            Map<String, Object> row = ordered.get(i);
            if (nodeId.equals(row.get("node_id")) && action.equals(row.get("action"))) {
                return i;
            }
        }
        return -1;
    }

    private int firstIndexOfAction(List<Map<String, Object>> ordered, String action) {
        for (int i = 0; i < ordered.size(); i++) {
            if (action.equals(ordered.get(i).get("action"))) {
                return i;
            }
        }
        return -1;
    }

    private long countAction(List<Map<String, Object>> ordered, String nodeId, String action) {
        return ordered.stream()
                .filter(row -> nodeId.equals(row.get("node_id")) && action.equals(row.get("action")))
                .count();
    }

    // ---------- AC-14：网关路由 + bootstrap 聚合（源级断言，SchedulerCore AC-13/14 手段先例） ----------

    @Test
    void should_route_workflow_surface_and_doc_group_without_internal_exposure() throws IOException {
        String gatewayYml = readSource("acme-gateway/src/main/resources/application.yml");
        assertThat(gatewayYml)
                .as("网关应含 /admin/v1/workflow/** → lb://platform-workflow 路由（确认⑧）")
                .contains("Path=/admin/v1/workflow/**")
                .contains("lb://platform-workflow");
        assertThat(gatewayYml)
                .as("网关应含工作流聚合文档分组 /v3/api-docs/workflow（spec §1 springdoc 分组）")
                .contains("/v3/api-docs/workflow")
                .contains("name: workflow");
        assertThat(gatewayYml)
                .as("/internal/** 不得进网关路由白名单（internal 面=服务间直连）")
                .doesNotContain("Path=/internal");

        Path workflowYml = acmeCloudRoot().resolve(
                "acme-platform/platform-workflow/platform-workflow-service/src/main/resources/application.yml");
        assertThat(Files.exists(workflowYml))
                .as("workflow 服务 application.yml 应存在（端口 9883 配置面）").isTrue();
    }

    @Test
    void should_aggregate_workflow_module_into_bootstrap() throws IOException {
        // ① acme-platform pom 登记 platform-workflow 模块
        String platformPom = readSource("acme-platform/pom.xml");
        assertThat(platformPom)
                .as("acme-platform pom 应登记 platform-workflow 模块")
                .contains("<module>platform-workflow</module>");

        // ② bootstrap pom 聚合 workflow 服务模块（第五 schema 聚合，对齐 JOB-1 四 schema 先例）
        String bootstrapPom = readSource("acme-bootstrap/pom.xml");
        assertThat(bootstrapPom)
                .as("acme-bootstrap pom 应含 platform-workflow-service 依赖")
                .contains("platform-workflow-service");

        // ③ 单体主类扫描面覆盖 com.acme.platform
        String monolith = readSource("acme-bootstrap/src/main/java/com/acme/bootstrap/MonolithApplication.java");
        assertThat(monolith)
                .as("单体扫描面应覆盖 com.acme.platform（workflow 组件随扫描聚合）")
                .contains("com.acme.platform");

        // ④ workflow 服务主类文件在场（A-1，已实证）
        assertThat(Files.exists(acmeCloudRoot().resolve(
                "acme-platform/platform-workflow/platform-workflow-service/src/main/java/"
                        + "com/acme/platform/workflow/PlatformWorkflowApplication.java")))
                .as("workflow 服务主类文件应存在").isTrue();
    }

    // ---------- 404/参数守卫（错误码域全段落锚） ----------

    @Test
    void should_return_not_found_when_operating_unknown_ids() throws Exception {
        bizFailCode(callAsSuperAdmin(get(DEFS_URL + "/999999999")),
                WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND, "未知定义详情应 01-0004");
        bizFailCode(callAsSuperAdmin(post(DEFS_URL + "/999999999/deploy")),
                WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND, "未知定义部署应 01-0004");
        bizFailCode(callAsSuperAdmin(get(INSTANCES_URL + "/999999999")),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, "未知实例详情应 02-0001");
        bizFailCode(callAsSuperAdmin(post(INSTANCES_URL + "/999999999/terminate")),
                WorkflowErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, "未知实例终止应 02-0001");
        bizFailCode(completeTask("2599", "default", Set.of(), 999999999L, null),
                WorkflowErrorCodes.WORKFLOW_TASK_NOT_FOUND, "未知任务完成应 03-0001");
    }

    @Test
    void should_reject_start_without_business_key() throws Exception {
        String key = uniq("bk-required");
        long defId = createAndDeploy(key, bpmn(key, SINGLE_TASK_BODY));
        // businessKey 必填（02-0004）：@NotBlank 绑定校验走框架 400 或业务信封——两形态均视为拒绝
        // （pitfalls ②：绑定非法值走框架翻译，业务信封 code≠0 为业务路径）
        ObjectNode body = bodyNode();
        body.put("definitionId", String.valueOf(defId));
        MvcResult result = performAs("1", "default", Set.of("super_admin"),
                post(INSTANCES_URL).contentType(JSON_TYPE)
                        .content(JSON.writeValueAsString(body)));
        int status = result.getResponse().getStatus();
        JsonNode respBody = bodyOf(result);
        if (status == 400) {
            assertThat(respBody.path("success").asBoolean())
                    .as("400 形态应为失败信封（框架翻译）").isFalse();
        } else {
            bizFailCode(respBody, WorkflowErrorCodes.WORKFLOW_BUSINESS_KEY_REQUIRED,
                    "缺 businessKey 启动应拒绝（02-0004）");
        }
    }

    // ---------- 源文件定位支撑（AC-14；SchedulerCore 同款） ----------

    private static Path acmeCloudRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("acme-gateway/src/main/resources/application.yml"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "无法定位 acme-cloud 根目录（user.dir=" + System.getProperty("user.dir") + "）");
    }

    private static String readSource(String relativePath) throws IOException {
        Path path = acmeCloudRoot().resolve(relativePath);
        assertThat(Files.exists(path))
                .as("源文件缺失（backend 未落盘或路径不符，疑点上报）：%s", path).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // ---------- 内嵌桩（JDK HttpServer，JOB-1 先例） ----------

    /** 委托/注册/注销请求记录。 */
    record RecordedRequest(String method, String path, String body, Instant receivedAt) {
    }

    /**
     * ServiceTask 目标端点桩：记录全部请求，恒 200 受理（业务侧受理形态；
     * 失败链路以内部回调 success=false 驱动，与契约回调协议一致）。
     */
    static final class ServiceStub {

        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

        private ServiceStub(HttpServer server) {
            this.server = server;
        }

        static ServiceStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ServiceStub stub = new ServiceStub(server);
                server.createContext("/", stub::handle);
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return stub;
            } catch (IOException ex) {
                throw new UncheckedIOException("内嵌 ServiceTask 桩启动失败", ex);
            }
        }

        String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        void reset() {
            requests.clear();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    Instant.now()));
            respond(exchange, 200, "{\"success\":true,\"code\":\"0\",\"message\":\"ok\",\"data\":{}}");
        }

        private void respond(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    /**
     * 调度中心桩（契约 §6 协议面）：POST /internal/v1/scheduler/jobs 一次性注册受理 +
     * DELETE /internal/v1/scheduler/jobs/{jobKey} 注销受理，记录全部请求供协议断言。
     */
    static final class SchedulerStub {

        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

        private SchedulerStub(HttpServer server) {
            this.server = server;
        }

        static SchedulerStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                SchedulerStub stub = new SchedulerStub(server);
                server.createContext("/", stub::handle);
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return stub;
            } catch (IOException ex) {
                throw new UncheckedIOException("内嵌调度中心桩启动失败", ex);
            }
        }

        String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        void reset() {
            requests.clear();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    Instant.now()));
            // 注册/注销均以 Result 信封受理（内部面=信封不过网关，契约惯例）
            respond(exchange, 200, "{\"success\":true,\"code\":\"0\",\"message\":\"ok\",\"data\":{}}");
        }

        private void respond(HttpExchange exchange, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
