package com.acme.platform.workflow.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.workflow.api.dto.HistoryActivityView;
import com.acme.platform.workflow.api.dto.ProcessInstanceView;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.command.ResumeInstanceCommand;
import com.acme.platform.workflow.application.command.StartInstanceCommand;
import com.acme.platform.workflow.application.command.SuspendInstanceCommand;
import com.acme.platform.workflow.application.command.TerminateInstanceCommand;
import com.acme.platform.workflow.application.command.WorkflowPayloads;
import com.acme.platform.workflow.domain.WfHistoryActivity;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.engine.VariableScope;
import com.acme.platform.workflow.infrastructure.repository.WfExecutionRepository;
import com.acme.platform.workflow.infrastructure.repository.WfHistoryActivityRepository;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.platform.workflow.infrastructure.repository.WfProcessInstanceRepository;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.infrastructure.repository.WfVariableRepository;
import com.acme.platform.workflow.kernel.WorkflowCommandExecutor;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例域用例（写路径走命令内核；查询直查——详情=实例+活跃节点+任务树+变量摘要，契约 §4）。
 */
@Service("workflowInstanceUseCase")
public class InstanceUseCase {

    private final WorkflowCommandExecutor executor;
    private final StartInstanceCommand startInstanceCommand;
    private final TerminateInstanceCommand terminateInstanceCommand;
    private final SuspendInstanceCommand suspendInstanceCommand;
    private final ResumeInstanceCommand resumeInstanceCommand;
    private final WfProcessInstanceRepository instanceRepository;
    private final WfProcessDefinitionRepository definitionRepository;
    private final WfExecutionRepository executionRepository;
    private final WfTaskRepository taskRepository;
    private final WfHistoryActivityRepository historyRepository;
    private final WfVariableRepository variableRepository;
    private final JsonMapper jsonMapper;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public InstanceUseCase(WorkflowCommandExecutor executor,
                           StartInstanceCommand startInstanceCommand,
                           TerminateInstanceCommand terminateInstanceCommand,
                           SuspendInstanceCommand suspendInstanceCommand,
                           ResumeInstanceCommand resumeInstanceCommand,
                           WfProcessInstanceRepository instanceRepository,
                           WfProcessDefinitionRepository definitionRepository,
                           WfExecutionRepository executionRepository,
                           WfTaskRepository taskRepository,
                           WfHistoryActivityRepository historyRepository,
                           WfVariableRepository variableRepository,
                           JsonMapper jsonMapper) {
        this.executor = executor;
        this.startInstanceCommand = startInstanceCommand;
        this.terminateInstanceCommand = terminateInstanceCommand;
        this.suspendInstanceCommand = suspendInstanceCommand;
        this.resumeInstanceCommand = resumeInstanceCommand;
        this.instanceRepository = instanceRepository;
        this.definitionRepository = definitionRepository;
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.historyRepository = historyRepository;
        this.variableRepository = variableRepository;
        this.jsonMapper = jsonMapper;
    }

    /** 启动（返回实例 id）。 */
    public Long start(Long definitionId, String businessKey, Map<String, Object> variables) {
        return executor.execute(startInstanceCommand,
                new WorkflowPayloads.StartInstance(definitionId, businessKey, variables));
    }

    /** 详情（实例+活跃节点+任务树+变量摘要）。 */
    public ProcessInstanceView detail(Long instanceId) {
        WfProcessInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new com.acme.common.core.BizException(
                        com.acme.platform.workflow.WorkflowError.WORKFLOW_INSTANCE_NOT_FOUND,
                        "流程实例不存在：" + instanceId));
        var definition = definitionRepository.findById(instance.getDefinitionId()).orElse(null);
        var activeNodes = executionRepository.findActiveByInstanceId(instanceId).stream()
                .map(execution -> new ProcessInstanceView.ActiveNodeView(execution.getId(),
                        execution.getParentId(), execution.getNodeId(), execution.getNodeType(),
                        execution.getConcurrent(), execution.getScopeRoot()))
                .toList();
        var tasks = taskRepository.findActiveByInstanceId(instanceId).stream()
                .map(this::taskItem)
                .toList();
        return new ProcessInstanceView(instance.getId(), instance.getDefinitionId(),
                definition == null ? null : definition.getProcessKey(), instance.getBusinessKey(),
                instance.getInitiator(), instance.getStatus(), instance.getSuspendReason(),
                instance.getStartTime(), instance.getEndTime(), variableSummary(instanceId),
                activeNodes, tasks);
    }

    /** 分页（definitionKey/status/businessKey 过滤；start_time 排序）。 */
    public PageResult<ProcessInstanceView> list(String definitionKey, WorkflowInstanceStatus status,
                                                String businessKey, PageQuery pageQuery) {
        QueryWrapper query = QueryWrapper.create();
        if (definitionKey != null && !definitionKey.isBlank()) {
            List<Long> definitionIds = definitionRepository
                    .findAll(QueryWrapper.create()
                            .where(WfProcessDefinition::getProcessKey).eq(definitionKey.trim()))
                    .stream().map(WfProcessDefinition::getId).toList();
            if (definitionIds.isEmpty()) {
                return PageResult.empty();
            }
            query = query.and(WfProcessInstance::getDefinitionId).in(definitionIds);
        }
        if (status != null) {
            query = query.and(WfProcessInstance::getStatus).eq(status);
        }
        if (businessKey != null && !businessKey.isBlank()) {
            query = query.and(WfProcessInstance::getBusinessKey).eq(businessKey.trim());
        }
        query = query.orderBy(WfProcessInstance::getStartTime, false);
        var page = instanceRepository.findAll(query, pageQuery.page(), pageQuery.size());
        return new PageResult<>(page.records().stream().map(instance ->
                new ProcessInstanceView(instance.getId(), instance.getDefinitionId(), definitionKey,
                        instance.getBusinessKey(), instance.getInitiator(), instance.getStatus(),
                        instance.getSuspendReason(), instance.getStartTime(), instance.getEndTime(),
                        null, List.of(), List.of())).toList(), page.total());
    }

    /** 我发起（initiator=me）。 */
    public PageResult<ProcessInstanceView> initiated(String me, PageQuery pageQuery) {
        QueryWrapper query = QueryWrapper.create()
                .where(WfProcessInstance::getInitiator).eq(me)
                .orderBy(WfProcessInstance::getStartTime, false);
        var page = instanceRepository.findAll(query, pageQuery.page(), pageQuery.size());
        return new PageResult<>(page.records().stream().map(instance ->
                new ProcessInstanceView(instance.getId(), instance.getDefinitionId(), null,
                        instance.getBusinessKey(), instance.getInitiator(), instance.getStatus(),
                        instance.getSuspendReason(), instance.getStartTime(), instance.getEndTime(),
                        null, List.of(), List.of())).toList(), page.total());
    }

    /** 终止（撤回语义面）。 */
    public Long terminate(Long instanceId) {
        return executor.execute(terminateInstanceCommand,
                new WorkflowPayloads.TerminateInstance(instanceId));
    }

    /** 挂起。 */
    public Long suspend(Long instanceId, String reason) {
        return executor.execute(suspendInstanceCommand,
                new WorkflowPayloads.SuspendInstance(instanceId, reason));
    }

    /** 恢复。 */
    public Long resume(Long instanceId) {
        return executor.execute(resumeInstanceCommand, new WorkflowPayloads.ResumeInstance(instanceId));
    }

    /** 历史活动链（AC-15 按序完整）。 */
    public List<HistoryActivityView> history(Long instanceId) {
        return historyRepository.findByInstanceIdOrdered(instanceId).stream()
                .map(this::historyView)
                .toList();
    }

    private HistoryActivityView historyView(WfHistoryActivity row) {
        Map<String, Object> detail = null;
        if (row.getDetailJson() != null && !row.getDetailJson().isEmpty()) {
            try {
                detail = jsonMapper.readValue(row.getDetailJson(), Map.class);
            } catch (RuntimeException ex) {
                detail = null;
            }
        }
        return new HistoryActivityView(row.getId(), row.getNodeId(), row.getNodeType(),
                row.getAction(), row.getAssignee(), row.getStartAt(), row.getEndAt(), detail);
    }

    /** 变量摘要（根作用域当前值）。 */
    private Map<String, Object> variableSummary(Long instanceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var variable : variableRepository.findByInstanceId(instanceId)) {
            if (variable.getName().startsWith(VariableScope.SCOPE_PREFIX)) {
                continue;
            }
            try {
                result.put(variable.getName(),
                        variable.getValueJson() == null ? null
                                : jsonMapper.readValue(variable.getValueJson(), Object.class));
            } catch (RuntimeException ex) {
                result.put(variable.getName(), variable.getValueJson());
            }
        }
        return result;
    }

    private ProcessInstanceView.TaskItemView taskItem(WfTask task) {
        Map<String, Object> formData = null;
        if (task.getFormDataJson() != null && !task.getFormDataJson().isEmpty()) {
            try {
                formData = jsonMapper.readValue(task.getFormDataJson(), Map.class);
            } catch (RuntimeException ex) {
                formData = null;
            }
        }
        return new ProcessInstanceView.TaskItemView(task.getId(), task.getInstanceId(),
                task.getExecutionId(), task.getNodeId(), task.getNodeName(), task.getAssignee(),
                task.getStatus(), formData, task.getDueDate(), task.getClaimTime(),
                task.getCompleteTime(), task.getCreatedAt());
    }
}
