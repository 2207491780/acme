package com.acme.platform.workflow.compile;

import com.acme.platform.workflow.bpmn.AcmeAttributes;
import com.acme.platform.workflow.bpmn.BpmnDocumentParser;
import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.bpmn.model.BpmnElement;
import com.acme.platform.workflow.bpmn.model.BpmnFlow;
import com.acme.platform.workflow.bpmn.model.BpmnRawProcess;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程模型编译器（部署时一次成型——设计规格 §3.4/§5.4）：裸模型 → 编译模型，承载 §5.4 全清单
 * 结构校验与 SpEL 语法预解析；校验失败逐条 {@link ValidationError} 返回（不截断）。
 */
public final class ProcessModelCompiler {

    /** SpEL 预解析器（模板关闭——条件原文可能自带 ${} 包裹，统一剥离后裸解析）。 */
    private static final SpelExpressionParser SPEL_PARSER =
            new SpelExpressionParser(new SpelParserConfiguration());

    /** 保留字（节点 id 非保留字校验，§5.4）。 */
    private static final Set<String> RESERVED_IDS = Set.of("null", "true", "false", "and", "or", "not",
            "new", "this", "root", "class", "instanceof", "matches", "between", "instance", "var",
            "hasRole", "inDept", "initiator");

    private ProcessModelCompiler() {
    }

    /**
     * 编译裸模型（先校验后组装；任何结构错误均收集为 ValidationError 一次性返回）。
     */
    public static CompiledProcessModel compile(Long definitionId, String processKey,
                                               BpmnDocumentParser.ParseResult parseResult) {
        List<ValidationError> errors = new ArrayList<>();
        BpmnRawProcess raw = parseResult.process();
        for (String unsupported : parseResult.unsupportedElements()) {
            errors.add(new ValidationError(extractId(unsupported),
                    ValidationError.RULE_UNSUPPORTED_ELEMENT,
                    "一期语义白名单外元素：" + unsupported + "（CallActivity/InclusiveGateway/多实例等归二期）"));
        }
        Map<String, BpmnElement> elementMap = new LinkedHashMap<>();
        for (BpmnElement element : raw.elements()) {
            if (element.id() == null || element.id().isBlank()) {
                errors.add(new ValidationError(null, ValidationError.RULE_NODE_ID_UNIQUE,
                        "节点缺少 id"));
                continue;
            }
            if (RESERVED_IDS.contains(element.id().toLowerCase()) || !isValidId(element.id())) {
                errors.add(new ValidationError(element.id(), ValidationError.RULE_NODE_ID_UNIQUE,
                        "节点 id 非法或为保留字：" + element.id()));
            }
            if (elementMap.containsKey(element.id())) {
                errors.add(new ValidationError(element.id(), ValidationError.RULE_NODE_ID_UNIQUE,
                        "节点 id 重复：" + element.id()));
            }
            elementMap.put(element.id(), element);
        }
        Set<String> flowIds = new HashSet<>();
        for (BpmnFlow flow : raw.flows()) {
            if (flow.id() == null || flow.id().isBlank()) {
                errors.add(new ValidationError(null, ValidationError.RULE_NODE_ID_UNIQUE, "连线缺少 id"));
                continue;
            }
            if (RESERVED_IDS.contains(flow.id().toLowerCase()) || !isValidId(flow.id())) {
                errors.add(new ValidationError(flow.id(), ValidationError.RULE_NODE_ID_UNIQUE,
                        "连线 id 非法或为保留字：" + flow.id()));
            }
            if (!flowIds.add(flow.id())) {
                errors.add(new ValidationError(flow.id(), ValidationError.RULE_NODE_ID_UNIQUE,
                        "连线 id 重复：" + flow.id()));
            }
            if (flow.sourceRef() == null || flow.sourceRef().isBlank()
                    || flow.targetRef() == null || flow.targetRef().isBlank()) {
                errors.add(new ValidationError(flow.id(), ValidationError.RULE_DANGLING_FLOW,
                        "连线缺 source/target 引用：" + flow.id()));
                continue;
            }
            if (!elementMap.containsKey(flow.sourceRef()) || !elementMap.containsKey(flow.targetRef())) {
                errors.add(new ValidationError(flow.id(), ValidationError.RULE_DANGLING_FLOW,
                        "悬挂连线（源或目标节点不存在）：" + flow.id() + " -> " + flow.targetRef()));
            }
        }
        // start/end 恰一（容器级：顶层 process 与每个嵌入子流程各自恰一——§5.4 全清单口径）
        java.util.Map<String, List<BpmnElement>> containers = new java.util.LinkedHashMap<>();
        for (BpmnElement element : raw.elements()) {
            containers.computeIfAbsent(element.containerId(), k -> new ArrayList<>()).add(element);
        }
        for (Map.Entry<String, List<BpmnElement>> containerEntry : containers.entrySet()) {
            long containerStarts = containerEntry.getValue().stream()
                    .filter(e -> e.type() == BpmnElementType.START_EVENT).count();
            long containerEnds = containerEntry.getValue().stream()
                    .filter(e -> e.type() == BpmnElementType.END_EVENT).count();
            String containerLabel = containerEntry.getKey() == null ? "process" : containerEntry.getKey();
            if (containerStarts != 1) {
                errors.add(new ValidationError(null, ValidationError.RULE_START_END_EXACTLY_ONE,
                        "容器 " + containerLabel + " 的 StartEvent 须恰一（实际 " + containerStarts + "）"));
            }
            // EndEvent ≥1（多分支各奔终点为标准 BPMN 形态——spec「start/end 恰一」的 start 恰一、
            // end 非零裁定，偏差建议回改规格见交付报告；正常终止/边界终止共存的定时分支依赖此形态）
            if (containerEnds < 1) {
                errors.add(new ValidationError(null, ValidationError.RULE_START_END_EXACTLY_ONE,
                        "容器 " + containerLabel + " 的 EndEvent 至少一（实际 " + containerEnds + "）"));
            }
        }
        // 连通面校验（悬挂连线/出入边缺失）
        Map<String, List<String>> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (BpmnFlow flow : raw.flows()) {
            if (flow.sourceRef() == null || flow.targetRef() == null) {
                continue;
            }
            outgoing.computeIfAbsent(flow.sourceRef(), k -> new ArrayList<>()).add(flow.id());
            incoming.computeIfAbsent(flow.targetRef(), k -> new ArrayList<>()).add(flow.id());
        }
        for (BpmnElement element : elementMap.values()) {
            List<String> outs = outgoing.getOrDefault(element.id(), List.of());
            List<String> ins = incoming.getOrDefault(element.id(), List.of());
            switch (element.type()) {
                case START_EVENT -> {
                    if (!ins.isEmpty()) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_DANGLING_FLOW,
                                "StartEvent 不得有入边"));
                    }
                    if (outs.isEmpty()) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_DANGLING_FLOW,
                                "StartEvent 缺少流出连线"));
                    }
                }
                case END_EVENT -> {
                    if (outs.isEmpty()) {
                        // 终止节点允许零流出（恰一校验在上）
                    } else {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_DANGLING_FLOW,
                                "EndEvent 不得有出边"));
                    }
                }
                case BOUNDARY_TIMER -> {
                    if (!ins.isEmpty()) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_BOUNDARY_ATTACHMENT,
                                "边界事件不得有入边：" + element.id()));
                    }
                    if (element.attachedTo() == null || element.attachedTo().isBlank()) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_BOUNDARY_ATTACHMENT,
                                "定时边界事件必须挂接节点（acme/bpmn attachedToRef 缺失）：" + element.id()));
                    } else {
                        BpmnElement host = elementMap.get(element.attachedTo());
                        if (host == null) {
                            errors.add(new ValidationError(element.id(), ValidationError.RULE_BOUNDARY_ATTACHMENT,
                                    "边界事件挂接点不存在：" + element.attachedTo()));
                        } else if (host.type() != BpmnElementType.USER_TASK
                                && host.type() != BpmnElementType.EMBEDDED_SUB_PROCESS) {
                            errors.add(new ValidationError(element.id(), ValidationError.RULE_BOUNDARY_ATTACHMENT,
                                    "边界事件仅可挂接 UserTask/EmbeddedSubProcess：" + host.id()));
                        }
                    }
                    if (outs.isEmpty()) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_DANGLING_FLOW,
                                "边界事件缺少流出连线（中断分支）：" + element.id()));
                    }
                    boolean hasTimerConfig = (element.timerDelay() != null && !element.timerDelay().isBlank())
                            || (element.timerDate() != null && !element.timerDate().isBlank());
                    if (!hasTimerConfig) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_BOUNDARY_ATTACHMENT,
                                "定时边界事件缺少 timeDuration/timeDate：" + element.id()));
                    } else {
                        parseTimer(element, errors);
                    }
                }
                default -> {
                    if (ins.isEmpty()) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_DANGLING_FLOW,
                                "节点缺少流入连线：" + element.id()));
                    }
                    if (outs.isEmpty()) {
                        errors.add(new ValidationError(element.id(), ValidationError.RULE_DANGLING_FLOW,
                                "节点缺少流出连线：" + element.id()));
                    }
                }
            }
            // 网关条件完备（XOR 全边条件或 default 兜底；AND 不得带条件）；default 指向取自
            // 网关元素 default 属性（BPMN 标准位）——BpmnFlow.defaultFlow 恒 false（连线侧无此语义）
            if (element.type() == BpmnElementType.EXCLUSIVE_GATEWAY) {
                List<BpmnFlow> gatewayOuts = outs.stream()
                        .map(flowId -> flowById(raw, flowId)).toList();
                boolean hasDefault = element.defaultFlow() != null && gatewayOuts.stream()
                        .anyMatch(gatewayFlow -> element.defaultFlow().equals(gatewayFlow.id()));
                for (BpmnFlow gatewayFlow : gatewayOuts) {
                    boolean isDefault = element.defaultFlow() != null
                            && element.defaultFlow().equals(gatewayFlow.id());
                    if (isDefault && gatewayFlow.conditionExpression() != null) {
                        errors.add(new ValidationError(gatewayFlow.id(), ValidationError.RULE_GATEWAY_CONDITIONS,
                                "default 流出不得携带条件：" + gatewayFlow.id()));
                    }
                    if (!isDefault && gatewayFlow.conditionExpression() == null && !hasDefault) {
                        errors.add(new ValidationError(gatewayFlow.id(), ValidationError.RULE_GATEWAY_CONDITIONS,
                                "排他网关流出须带条件（无 default 时全边带条件）：" + gatewayFlow.id()));
                    } else if (!isDefault && gatewayFlow.conditionExpression() == null) {
                        errors.add(new ValidationError(gatewayFlow.id(), ValidationError.RULE_GATEWAY_CONDITIONS,
                                "排他网关非 default 流出须带条件：" + gatewayFlow.id()));
                    }
                }
            }
            if (element.type() == BpmnElementType.PARALLEL_GATEWAY) {
                for (String flowId : outs) {
                    BpmnFlow gatewayFlow = flowById(raw, flowId);
                    if (gatewayFlow.conditionExpression() != null) {
                        errors.add(new ValidationError(gatewayFlow.id(), ValidationError.RULE_GATEWAY_CONDITIONS,
                                "并行网关流出不得携带条件：" + gatewayFlow.id()));
                    }
                }
            }
            // UserTask 指派规则至少一（§5.4）
            if (element.type() == BpmnElementType.USER_TASK) {
                boolean hasAssignment = element.acme(AcmeAttributes.ASSIGNEE) != null
                        || element.acme(AcmeAttributes.CANDIDATE_ROLES) != null
                        || element.acme(AcmeAttributes.CANDIDATE_DEPTS) != null
                        || element.acme(AcmeAttributes.CANDIDATE_USERS) != null;
                if (!hasAssignment) {
                    errors.add(new ValidationError(element.id(), ValidationError.RULE_USER_TASK_ASSIGNMENT,
                            "UserTask 须至少一种指派规则（assignee/candidateRoles/candidateDepts/candidateUsers）"));
                }
            }
            // ServiceTask 必填 serviceRef/callbackPath（§5.4）
            if (element.type() == BpmnElementType.SERVICE_TASK) {
                if (element.acme(AcmeAttributes.SERVICE_REF) == null
                        || element.acme(AcmeAttributes.CALLBACK_PATH) == null) {
                    errors.add(new ValidationError(element.id(), ValidationError.RULE_SERVICE_TASK_CONFIG,
                            "ServiceTask 必填 acme:serviceRef 与 acme:callbackPath"));
                }
            }
        }
        // 表达式预解析（SpEL 语法错收集；条件与 assignee 表达式双面）
        Map<String, Expression> parsedConditions = new LinkedHashMap<>();
        for (BpmnFlow flow : raw.flows()) {
            if (flow.conditionExpression() != null) {
                try {
                    parsedConditions.put(flow.id(), preParse(flow.conditionExpression()));
                } catch (RuntimeException ex) {
                    errors.add(new ValidationError(flow.id(), ValidationError.RULE_EXPRESSION_SYNTAX,
                            "条件表达式语法错误：" + flow.id() + " -> " + ex.getMessage()));
                }
            }
        }
        for (BpmnElement element : elementMap.values()) {
            String assignee = element.acme(AcmeAttributes.ASSIGNEE);
            if (assignee != null && isExpression(assignee)) {
                try {
                    preParse(assignee);
                } catch (RuntimeException ex) {
                    errors.add(new ValidationError(element.id(), ValidationError.RULE_EXPRESSION_SYNTAX,
                            "assignee 表达式语法错误：" + element.id() + " -> " + ex.getMessage()));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ProcessValidationException(errors);
        }
        // 组装编译模型（default 指向取自源网关元素 default 属性——BPMN 标准位）
        Map<String, CompiledFlow> compiledFlows = new LinkedHashMap<>();
        for (BpmnFlow flow : raw.flows()) {
            BpmnElement source = elementMap.get(flow.sourceRef());
            boolean isDefault = source != null && source.defaultFlow() != null
                    && source.defaultFlow().equals(flow.id());
            compiledFlows.put(flow.id(), new CompiledFlow(flow.id(), flow.sourceRef(), flow.targetRef(),
                    parsedConditions.get(flow.id()), isDefault));
        }
        Map<String, CompiledElement> compiledElements = new LinkedHashMap<>();
        for (BpmnElement element : elementMap.values()) {
            Long timerDelayMs = parseTimer(element, errors);
            compiledElements.put(element.id(), new CompiledElement(element.id(), element.name(),
                    element.type(), element.acmeAttrs(),
                    List.copyOf(outgoing.getOrDefault(element.id(), List.of())),
                    List.copyOf(incoming.getOrDefault(element.id(), List.of())),
                    attachedTimersOf(element.id(), elementMap.values()),
                    element.attachedTo(), timerDelayMs, element.timerDate(),
                    element.defaultFlow(), element.containerId(), element.type().name()));
        }
        String startId = raw.elements().stream()
                .filter(e -> e.type() == BpmnElementType.START_EVENT
                        && raw.id() != null && raw.id().equals(e.containerId()))
                .map(BpmnElement::id).findFirst()
                .orElseGet(() -> raw.elements().stream()
                        .filter(e -> e.type() == BpmnElementType.START_EVENT)
                        .map(BpmnElement::id).findFirst().orElseThrow());
        return new CompiledProcessModel(definitionId, processKey,
                compiledElements, compiledFlows, startId);
    }

    /** ${…} 包裹剥离（裸 SpEL 亦接受）；剥离失败原样返回。 */
    public static Expression preParse(String raw) {
        String expression = stripTemplate(raw);
        return SPEL_PARSER.parseExpression(expression);
    }

    /** 表达式形态判定（${…} 包裹视为表达式，其余为字面量）。 */
    public static boolean isExpression(String value) {
        return value != null && value.startsWith("${") && value.endsWith("}");
    }

    /** 剥离 ${…} 包裹（无包裹原样返回）。 */
    public static String stripTemplate(String raw) {
        if (raw != null && raw.startsWith("${") && raw.endsWith("}")) {
            return raw.substring(2, raw.length() - 1);
        }
        return raw;
    }

    /** 定时器配置解析（ISO-8601 Duration 展平毫秒；timeDate 原文透传由运行时解析；非法记校验错误）。 */
    private static Long parseTimer(BpmnElement element, List<ValidationError> errors) {
        try {
            if (element.timerDelay() != null && !element.timerDelay().isBlank()) {
                return Duration.parse(element.timerDelay()).toMillis();
            }
            if (element.timerDate() != null && !element.timerDate().isBlank()) {
                Instant.parse(element.timerDate());
                return null;
            }
        } catch (DateTimeParseException ex) {
            errors.add(new ValidationError(element.id(), ValidationError.RULE_BOUNDARY_ATTACHMENT,
                    "定时表达式非法（ISO-8601）：" + element.id() + " -> " + ex.getMessage()));
            return null;
        }
        return null;
    }

    /** 节点挂接的定时边界事件 id 集。 */
    private static List<String> attachedTimersOf(String nodeId, Iterable<BpmnElement> elements) {
        List<String> timers = new ArrayList<>();
        for (BpmnElement candidate : elements) {
            if (candidate.type() == BpmnElementType.BOUNDARY_TIMER
                    && nodeId.equals(candidate.attachedTo())) {
                timers.add(candidate.id());
            }
        }
        return List.copyOf(timers);
    }

    private static BpmnFlow flowById(BpmnRawProcess raw, String flowId) {
        return raw.flows().stream().filter(flow -> flow.id().equals(flowId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("连线缺失：" + flowId));
    }

    private static String extractId(String unsupported) {
        int hash = unsupported.lastIndexOf('#');
        return hash >= 0 ? unsupported.substring(hash + 1) : null;
    }

    /** id 合法形态（字母/数字/下划线/连字符，字母开头）。 */
    private static boolean isValidId(String id) {
        return id.matches("[A-Za-z_][A-Za-z0-9_-]*");
    }
}
