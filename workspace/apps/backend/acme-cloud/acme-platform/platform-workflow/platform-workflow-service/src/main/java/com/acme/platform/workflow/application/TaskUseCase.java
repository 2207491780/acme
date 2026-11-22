package com.acme.platform.workflow.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.api.dto.TaskView;
import com.acme.platform.workflow.application.command.ClaimTaskCommand;
import com.acme.platform.workflow.application.command.CompleteTaskCommand;
import com.acme.platform.workflow.application.command.DelegateTaskCommand;
import com.acme.platform.workflow.application.command.RejectTaskCommand;
import com.acme.platform.workflow.application.command.TransferTaskCommand;
import com.acme.platform.workflow.application.command.UnclaimTaskCommand;
import com.acme.platform.workflow.application.command.WorkflowPayloads;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.platform.workflow.infrastructure.repository.WfProcessInstanceRepository;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.kernel.WorkflowCommandExecutor;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务域用例（写路径走命令内核；todo/done/initiated 的「我」语义=当前登录用户
 * （TenantContext+UserContext，MeController 取 me 同机制）；候选可见性=编译模型指派规则 +
 * 身份目录解析（ALL 策略——候选含我即可见可办））。
 */
@Service("workflowTaskUseCase")
public class TaskUseCase {

    private final WorkflowCommandExecutor executor;
    private final CompleteTaskCommand completeTaskCommand;
    private final RejectTaskCommand rejectTaskCommand;
    private final ClaimTaskCommand claimTaskCommand;
    private final UnclaimTaskCommand unclaimTaskCommand;
    private final TransferTaskCommand transferTaskCommand;
    private final DelegateTaskCommand delegateTaskCommand;
    private final WfTaskRepository taskRepository;
    private final WfProcessInstanceRepository instanceRepository;
    private final WfProcessDefinitionRepository definitionRepository;
    private final com.acme.platform.workflow.engine.AssignmentResolver assignmentResolver;
    private final com.acme.platform.workflow.application.WorkflowCommandSupport support;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public TaskUseCase(WorkflowCommandExecutor executor,
                       CompleteTaskCommand completeTaskCommand,
                       RejectTaskCommand rejectTaskCommand,
                       ClaimTaskCommand claimTaskCommand,
                       UnclaimTaskCommand unclaimTaskCommand,
                       TransferTaskCommand transferTaskCommand,
                       DelegateTaskCommand delegateTaskCommand,
                       WfTaskRepository taskRepository,
                       WfProcessInstanceRepository instanceRepository,
                       WfProcessDefinitionRepository definitionRepository,
                       com.acme.platform.workflow.engine.AssignmentResolver assignmentResolver,
                       com.acme.platform.workflow.application.WorkflowCommandSupport support) {
        this.executor = executor;
        this.completeTaskCommand = completeTaskCommand;
        this.rejectTaskCommand = rejectTaskCommand;
        this.claimTaskCommand = claimTaskCommand;
        this.unclaimTaskCommand = unclaimTaskCommand;
        this.transferTaskCommand = transferTaskCommand;
        this.delegateTaskCommand = delegateTaskCommand;
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.definitionRepository = definitionRepository;
        this.assignmentResolver = assignmentResolver;
        this.support = support;
    }

    /**
     * 我的待办（assignee=me 或候选含我，租户隔离；definitionKey/status 可选过滤——确认⑤）。
     * 过滤与投影装配共用 instance/definition 批量加载（页级两次 in 查询，禁 N+1 逐条）。
     */
    public PageResult<TaskView> todo(String me, PageQuery pageQuery, String definitionKey,
                                     WorkflowTaskStatus status) {
        List<WfTask> assigned = taskRepository.findByAssigneeAndStatusIn(me,
                List.of(WorkflowTaskStatus.CREATED, WorkflowTaskStatus.CLAIMED));
        List<WfTask> candidates = taskRepository
                .findByStatusInAndAssigneeIsNull(List.of(WorkflowTaskStatus.CREATED)).stream()
                .filter(task -> isCandidateFor(task, me))
                .toList();
        List<WfTask> merged = new ArrayList<>(assigned);
        merged.addAll(candidates);
        merged.sort(Comparator.comparing(WfTask::getId, Comparator.reverseOrder()));
        TaskProjection projection = projectionOf(merged);
        if (definitionKey != null && !definitionKey.isBlank()) {
            String key = definitionKey.trim();
            merged = merged.stream()
                    .filter(task -> key.equals(projection.definitionKeyOf(task)))
                    .toList();
        }
        if (status != null) {
            merged = merged.stream().filter(task -> task.getStatus() == status).toList();
        }
        return paginate(merged, pageQuery, projection);
    }

    /** 我的已办（COMPLETED 且完成时 assignee=me——隐式认领锚点；definitionKey/status 可选过滤——确认⑤）。 */
    public PageResult<TaskView> done(String me, PageQuery pageQuery, String definitionKey,
                                     WorkflowTaskStatus status) {
        QueryWrapper query = QueryWrapper.create()
                .where(WfTask::getAssignee).eq(me)
                .and(WfTask::getStatus).eq(WorkflowTaskStatus.COMPLETED);
        if (status != null) {
            query = query.and(WfTask::getStatus).eq(status);
        }
        if (definitionKey != null && !definitionKey.isBlank()) {
            List<Long> definitionIds = definitionRepository
                    .findAll(QueryWrapper.create()
                            .where(WfProcessDefinition::getProcessKey).eq(definitionKey.trim()))
                    .stream().map(WfProcessDefinition::getId).toList();
            List<Long> instanceIds = definitionIds.isEmpty() ? List.of()
                    : instanceRepository.findAll(QueryWrapper.create()
                            .where(WfProcessInstance::getDefinitionId).in(definitionIds))
                    .stream().map(WfProcessInstance::getId).toList();
            if (instanceIds.isEmpty()) {
                return PageResult.empty();
            }
            query = query.and(WfTask::getInstanceId).in(instanceIds);
        }
        query = query.orderBy(WfTask::getCompleteTime, false);
        var page = taskRepository.findAll(query, pageQuery.page(), pageQuery.size());
        TaskProjection projection = projectionOf(page.records());
        return new PageResult<>(page.records().stream()
                .map(task -> viewOf(task, projection)).toList(), page.total());
    }

    /** 办结。 */
    public Long complete(Long taskId, java.util.Map<String, Object> form) {
        return executor.execute(completeTaskCommand, new WorkflowPayloads.CompleteTask(taskId, form));
    }

    /** 驳回（缺省回退/指定回退）。 */
    public Long reject(Long taskId, String targetNodeId, String comment) {
        return executor.execute(rejectTaskCommand,
                new WorkflowPayloads.RejectTask(taskId, targetNodeId, comment));
    }

    /** 认领。 */
    public Long claim(Long taskId) {
        return executor.execute(claimTaskCommand, new WorkflowPayloads.ClaimTask(taskId));
    }

    /** 撤认。 */
    public Long unclaim(Long taskId) {
        return executor.execute(unclaimTaskCommand, new WorkflowPayloads.ClaimTask(taskId));
    }

    /** 转办。 */
    public String transfer(Long taskId, String assignee) {
        return executor.execute(transferTaskCommand, new WorkflowPayloads.TransferTask(taskId, assignee));
    }

    /** 委派（转办+owner 记录，委托链单层）。 */
    public String delegate(Long taskId, String assignee) {
        return executor.execute(delegateTaskCommand, new WorkflowPayloads.TransferTask(taskId, assignee));
    }

    /** 候选可见性判定（模型指派规则 + 实例变量快照 + 身份目录）。 */
    private boolean isCandidateFor(WfTask task, String me) {
        try {
            var instance = support.loadInstance(task.getInstanceId());
            var model = support.loadModel(instance.getDefinitionId());
            var runtime = support.buildRuntime(null, model, instance, instance.getTenantId());
            return assignmentResolver.isCandidate(model.element(task.getNodeId()), runtime.variables(),
                    instance.getInitiator(), me);
        } catch (RuntimeException ex) {
            // 定义已禁用/模型缺失等异常面：不可见（fail-closed）
            return false;
        }
    }

    private PageResult<TaskView> paginate(List<WfTask> tasks, PageQuery pageQuery, TaskProjection projection) {
        int from = Math.min((pageQuery.page() - 1) * pageQuery.size(), tasks.size());
        int to = Math.min(from + pageQuery.size(), tasks.size());
        List<TaskView> records = tasks.subList(from, to).stream()
                .map(task -> viewOf(task, projection)).toList();
        return new PageResult<>(records, (long) tasks.size());
    }

    private TaskView viewOf(WfTask task, TaskProjection projection) {
        WfProcessInstance instance = projection.instances.get(task.getInstanceId());
        WfProcessDefinition definition = instance == null ? null
                : projection.definitions.get(instance.getDefinitionId());
        return new TaskView(task.getId(), task.getInstanceId(), task.getNodeId(), task.getNodeName(),
                task.getAssignee(), task.getStatus(), task.getClaimTime(), task.getCompleteTime(),
                task.getCreatedAt(),
                definition == null ? null : definition.getProcessKey(),
                definition == null ? null : definition.getName(),
                instance == null ? null : instance.getBusinessKey(),
                instance == null ? null : instance.getInitiator());
    }

    /**
     * 投影装配（确认⑤）：instance+definition 页级批量加载（各一次 in 查询），禁 N+1 逐条。
     * instance 租户过滤随查询面；definition 平台级共享。
     */
    private TaskProjection projectionOf(List<WfTask> tasks) {
        List<Long> instanceIds = tasks.stream().map(WfTask::getInstanceId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, WfProcessInstance> instances = instanceIds.isEmpty() ? Map.of()
                : instanceRepository.findAllById(instanceIds).stream()
                        .collect(Collectors.toMap(WfProcessInstance::getId, Function.identity()));
        List<Long> definitionIds = instances.values().stream()
                .map(WfProcessInstance::getDefinitionId).filter(Objects::nonNull).distinct().toList();
        Map<Long, WfProcessDefinition> definitions = definitionIds.isEmpty() ? Map.of()
                : definitionRepository.findAllById(definitionIds).stream()
                        .collect(Collectors.toMap(WfProcessDefinition::getId, Function.identity()));
        return new TaskProjection(instances, definitions);
    }

    /** 页级投影快照（instance/definition 查找表）。 */
    private record TaskProjection(Map<Long, WfProcessInstance> instances,
                                  Map<Long, WfProcessDefinition> definitions) {

        String definitionKeyOf(WfTask task) {
            WfProcessInstance instance = instances.get(task.getInstanceId());
            WfProcessDefinition definition = instance == null ? null
                    : definitions.get(instance.getDefinitionId());
            return definition == null ? null : definition.getProcessKey();
        }
    }
}
