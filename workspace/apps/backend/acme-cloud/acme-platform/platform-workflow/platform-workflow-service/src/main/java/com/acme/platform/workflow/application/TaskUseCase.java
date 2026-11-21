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
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.kernel.WorkflowCommandExecutor;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final com.acme.platform.workflow.engine.AssignmentResolver assignmentResolver;
    private final com.acme.platform.workflow.application.WorkflowCommandSupport support;

    public TaskUseCase(WorkflowCommandExecutor executor,
                       CompleteTaskCommand completeTaskCommand,
                       RejectTaskCommand rejectTaskCommand,
                       ClaimTaskCommand claimTaskCommand,
                       UnclaimTaskCommand unclaimTaskCommand,
                       TransferTaskCommand transferTaskCommand,
                       DelegateTaskCommand delegateTaskCommand,
                       WfTaskRepository taskRepository,
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
        this.assignmentResolver = assignmentResolver;
        this.support = support;
    }

    /** 我的待办（assignee=me 或候选含我，租户隔离随查询面）。 */
    public PageResult<TaskView> todo(String me, PageQuery pageQuery) {
        List<WfTask> assigned = taskRepository.findByAssigneeAndStatusIn(me,
                List.of(WorkflowTaskStatus.CREATED, WorkflowTaskStatus.CLAIMED));
        List<WfTask> candidates = taskRepository
                .findByStatusInAndAssigneeIsNull(List.of(WorkflowTaskStatus.CREATED)).stream()
                .filter(task -> isCandidateFor(task, me))
                .toList();
        List<WfTask> merged = new ArrayList<>(assigned);
        merged.addAll(candidates);
        merged.sort(Comparator.comparing(WfTask::getId, Comparator.reverseOrder()));
        return paginate(merged, pageQuery);
    }

    /** 我的已办（COMPLETED 且完成时 assignee=me——隐式认领锚点）。 */
    public PageResult<TaskView> done(String me, PageQuery pageQuery) {
        QueryWrapper query = QueryWrapper.create()
                .where(WfTask::getAssignee).eq(me)
                .and(WfTask::getStatus).eq(WorkflowTaskStatus.COMPLETED)
                .orderBy(WfTask::getCompleteTime, false);
        var page = taskRepository.findAll(query, pageQuery.page(), pageQuery.size());
        return new PageResult<>(page.records().stream().map(this::viewOf).toList(), page.total());
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

    private PageResult<TaskView> paginate(List<WfTask> tasks, PageQuery pageQuery) {
        int from = Math.min((pageQuery.page() - 1) * pageQuery.size(), tasks.size());
        int to = Math.min(from + pageQuery.size(), tasks.size());
        List<TaskView> records = tasks.subList(from, to).stream().map(this::viewOf).toList();
        return new PageResult<>(records, (long) tasks.size());
    }

    private TaskView viewOf(WfTask task) {
        return new TaskView(task.getId(), task.getInstanceId(), task.getNodeId(), task.getNodeName(),
                task.getAssignee(), task.getStatus(), task.getClaimTime(), task.getCompleteTime(),
                task.getCreatedAt());
    }
}
