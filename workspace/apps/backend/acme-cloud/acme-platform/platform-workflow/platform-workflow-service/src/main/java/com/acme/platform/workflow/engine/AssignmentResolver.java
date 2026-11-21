package com.acme.platform.workflow.engine;

import com.acme.platform.workflow.bpmn.AcmeAttributes;
import com.acme.platform.workflow.compile.CompiledElement;
import com.acme.platform.workflow.expression.WorkflowExpressionEvaluator;
import com.acme.platform.workflow.infrastructure.client.WorkflowIdentityDirectory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * UserTask 指派规则解析器（设计规格 §2 指派规则终稿，按序生效）：
 * 1. acme:assignee（固定用户 id / ${initiator} / SpEL 表达式）→ 固定办理人；
 * 2. 候选组并集：candidateUsers ∪ candidateRoles(经身份目录展开) ∪ candidateDepts(同上)；
 * 候选语义 ALL（一期唯一策略：全部候选可见可办）；assignee 已定则跳过候选。
 * 解析失败（无候选）由调用方落 NO_ASSIGNEE_RESOLVED 挂起语义（AC-5）。
 */
@Component
public class AssignmentResolver {

    /** 解析结果（fixed=固定办理人；candidates=候选集，可能为空集）。 */
    public sealed interface Assignment {

        /** 固定办理人。 */
        record Fixed(String assignee) implements Assignment {
        }

        /** 候选集（ALL 策略：全部可见可办）。 */
        record Candidates(Set<String> userIds) implements Assignment {
        }
    }

    private final WorkflowExpressionEvaluator expressionEvaluator;
    private final WorkflowIdentityDirectory identityDirectory;

    public AssignmentResolver(WorkflowExpressionEvaluator expressionEvaluator,
                              WorkflowIdentityDirectory identityDirectory) {
        this.expressionEvaluator = expressionEvaluator;
        this.identityDirectory = identityDirectory;
    }

    /**
     * 解析指派规则（subject=表达式求值主体——实例发起人，协议终稿见交付报告）。
     */
    public Assignment resolve(CompiledElement element, VariableScope variables, String initiatorUserId) {
        String assigneeSpec = element.acme(AcmeAttributes.ASSIGNEE);
        if (assigneeSpec != null) {
            String resolved = expressionEvaluator.evaluateAssignee(assigneeSpec, variables, initiatorUserId);
            if (resolved != null && !resolved.isBlank()) {
                return new Assignment.Fixed(resolved.trim());
            }
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (String userId : splitCsv(element.acme(AcmeAttributes.CANDIDATE_USERS))) {
            candidates.add(userId);
        }
        for (String roleCode : splitCsv(element.acme(AcmeAttributes.CANDIDATE_ROLES))) {
            identityDirectory.userIdsByRole(roleCode)
                    .forEach(id -> candidates.add(String.valueOf(id)));
        }
        for (String deptId : splitCsv(element.acme(AcmeAttributes.CANDIDATE_DEPTS))) {
            identityDirectory.userIdsByDept(parseLong(deptId))
                    .forEach(id -> candidates.add(String.valueOf(id)));
        }
        return new Assignment.Candidates(candidates);
    }

    /** 候选可见性判定（todo 查询面：ALL 策略——候选含我即可见可办）。 */
    public boolean isCandidate(CompiledElement element, VariableScope variables,
                               String initiatorUserId, String candidateUserId) {
        if (!(resolve(element, variables, initiatorUserId) instanceof Assignment.Candidates candidates)) {
            return false;
        }
        return candidateUserId != null && candidates.userIds().contains(candidateUserId);
    }

    /** 逗号分隔拆分（去空白）。 */
    private static Set<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
