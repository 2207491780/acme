package com.acme.bootstrap.local;

import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.framework.core.support.ServiceIdentityScope;
import com.acme.platform.workflow.application.DefinitionUseCase;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.invoke.MethodHandles;
import java.util.Optional;

/**
 * 试点流程部署器（REQ-20261122-233425 确认⑤：leave-pilot BPMN 由聚合面自持自部署——定义部署
 * 用例归 workflow 服务实现，4.4.2 business 禁依赖 *-service，故部署器落于聚合面并实现
 * business-leave {@link LeaveProcessLauncher} SPI 供给申请面 definitionId）。
 *
 * <p>时序：ApplicationReadyEvent 幂等 ensureDeployed（已有 DEPLOYED 同键定义即复用；否则
 * 草稿→存 XML→部署，申请面惰性兜底再 ensure）。BPMN 内容=申请受理（${initiator} 指派）→
 * HR 备案 ServiceTask（serviceRef=acme.leave.service-ref 部署期惰性解析——与 handler 注册键
 * 同键单源；callbackPath=SDK 接收前缀尾段）→ 终点。context 口径：ServiceIdentityScope 服务
 * 身份主体（Q-D08；定义平台级共享无租户列）。</p>
 *
 * <p>⚠️ 解析时序（TL round-1 diagnostic）：serviceRef 键面含 {@code ${local.server.port}}
 * （RANDOM_PORT 运行期绑定）——本类属性一律 Environment 部署期惰性读取
 * （ApplicationReadyEvent 时点已绑定），禁 @Value 构造期注入（构造期未绑定会烘焙
 * server.port=0，致试点 BPMN serviceRef 失效）。</p>
 */
@Component("leavePilotProcessDeployer")
public class LeavePilotProcessDeployer implements LeaveProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /** 试点定义键（幂等复用锚点）。 */
    static final String PILOT_KEY = "leave-pilot";

    /** 试点 ServiceTask 节点 callbackPath（SDK 接收前缀尾段；handler 路由键=serviceRef）。 */
    static final String PILOT_CALLBACK_PATH = "/leave-file";

    private final DefinitionUseCase definitionUseCase;
    private final Environment environment;

    private volatile Long pilotDefinitionId;

    public LeavePilotProcessDeployer(DefinitionUseCase definitionUseCase, Environment environment) {
        this.definitionUseCase = definitionUseCase;
        this.environment = environment;
    }

    @Override
    public Long pilotDefinitionId() {
        Long id = pilotDefinitionId;
        return id != null ? id : ensureDeployed();
    }

    /** 启动即部署（幂等；失败显式日志——申请面惰性再 ensure 不静默降级）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            ensureDeployed();
        } catch (RuntimeException ex) {
            log.warn("试点流程部署失败（申请面将重试 ensure）：key={}", PILOT_KEY, ex);
        }
    }

    private synchronized Long ensureDeployed() {
        Long id = pilotDefinitionId;
        if (id != null) {
            return id;
        }
        Long existing = definitionUseCase.list(PILOT_KEY, WorkflowDefinitionStatus.DEPLOYED,
                        new com.acme.common.contract.PageQuery(1, 50)).records().stream()
                .filter(view -> PILOT_KEY.equals(view.key()))
                .findFirst()
                .map(view -> {
                    log.info("试点流程已部署（复用既有定义）：key={} definitionId={}", PILOT_KEY, view.id());
                    return view.id();
                })
                .orElse(null);
        if (existing != null) {
            pilotDefinitionId = existing;
            return existing;
        }
        return ServiceIdentityScope.runAs("business-leave", () -> {
            String bpmnXml = pilotBpmnXml();
            Long created = definitionUseCase.create(PILOT_KEY, "请假审批试点", bpmnXml);
            definitionUseCase.saveXml(created, bpmnXml, null);
            definitionUseCase.deploy(created);
            pilotDefinitionId = created;
            log.info("试点流程部署完成：key={} definitionId={}", PILOT_KEY, created);
            return created;
        });
    }

    /** 试点 BPMN（确认⑤：申请受理→HR 备案 ServiceTask 委托→终点；指派=发起人）。 */
    private String pilotBpmnXml() {
        String serviceRef = resolveServiceRef();
        String callbackPath = resolveReceivePathPrefix() + PILOT_CALLBACK_PATH;
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:acme="http://acme.dev/bpmn/ext/v1"
                             targetNamespace="http://acme.dev/bpmn/pilot">
                  <process id="leave-pilot" name="请假审批试点">
                    <startEvent id="start"/>
                    <sequenceFlow id="f0" sourceRef="start" targetRef="approval"/>
                    <userTask id="approval" name="部门审批" acme:assignee="${initiator}"/>
                    <sequenceFlow id="f1" sourceRef="approval" targetRef="hr-file"/>
                    <serviceTask id="hr-file" name="HR 备案" acme:serviceRef="%s" acme:callbackPath="%s"
                                 acme:retryMaxAttempts="3" acme:retryBackoffMs="1000"/>
                    <sequenceFlow id="f2" sourceRef="hr-file" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(serviceRef, callbackPath);
    }

    /**
     * 聚合自持直连地址（单体与引擎同 JVM）：主源=acme.leave.service-ref（与 handler 注册键同键
     * 单源）；缺省回落 acme.workflow.service-task.callback-base-url（同值）；均未配置 →
     * local.server.port/server.port 直拼。属性一律部署期惰性读取+占位符解析（类注时序口径）。
     */
    private String resolveServiceRef() {
        String serviceRef = environment.getProperty("acme.leave.service-ref");
        if (serviceRef != null && !serviceRef.isBlank()) {
            return environment.resolvePlaceholders(serviceRef.trim());
        }
        String callbackBaseUrl = environment.getProperty("acme.workflow.service-task.callback-base-url");
        if (callbackBaseUrl != null && !callbackBaseUrl.isBlank()) {
            return environment.resolvePlaceholders(callbackBaseUrl.trim());
        }
        String port = Optional.ofNullable(environment.getProperty("local.server.port"))
                .orElseGet(() -> environment.getProperty("server.port", "8080"));
        return "http://127.0.0.1:" + port;
    }

    /** SDK 接收前缀（部署期惰性读取；缺省同 SDK 侧缺省值）。 */
    private String resolveReceivePathPrefix() {
        String configured = environment.getProperty("acme.workflow.service-task.receive-path-prefix");
        return configured == null || configured.isBlank()
                ? "/internal/v1/service-tasks" : configured.trim();
    }
}
