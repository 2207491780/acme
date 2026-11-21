package com.acme.platform.workflow.expression;

import com.acme.platform.workflow.compile.ProcessModelCompiler;
import com.acme.platform.workflow.engine.VariableScope;
import com.acme.platform.workflow.infrastructure.client.WorkflowIdentityDirectory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流程表达式求值器（SpEL 受限求值上下文——设计规格 §3.5 安全红线终稿的实现位）：
 * 变量只读属性访问（裸属性名命中流程变量/initiator）+ 内置函数白名单方法
 * （var/hasRole/inDept/initiator，实例方法解析器限根对象面）；类型引用（T(...)）/构造器/
 * Bean 容器访问/表达式写入显式封禁（安全等价 SimpleEvaluationContext 白名单——Spring 7
 * 该类不支持自定义 PropertyAccessor 注入，实现偏差与理由见交付报告）。
 * 身份判定（hasRole/inDept）按求值主体 userId 经 {@link WorkflowIdentityDirectory} 本地快照解析。
 * 求值主体协议钉终稿（BPM-1）：条件表达式与 assignee 表达式统一以「流程实例发起人」为主体。
 */
@Component
public class WorkflowExpressionEvaluator {

    private final WorkflowIdentityDirectory identityDirectory;

    public WorkflowExpressionEvaluator(WorkflowIdentityDirectory identityDirectory) {
        this.identityDirectory = identityDirectory;
    }

    /**
     * 求值预解析表达式（编译模型携带的 AST；运行期零解析，AC-13）。
     */
    public Object evaluate(Expression expression, VariableScope variables, String subjectUserId) {
        return expression.getValue(buildContext(variables, subjectUserId), Object.class);
    }

    /** 布尔条件求值（null/false=假）。 */
    public boolean evaluateBoolean(Expression expression, VariableScope variables, String subjectUserId) {
        Object value = evaluate(expression, variables, subjectUserId);
        return Boolean.TRUE.equals(value);
    }

    /** assignee 表达式求值（字面量直接返回；${…} 表达式编译期已预校验、此处现场解析求值）。 */
    public String evaluateAssignee(String assigneeSpec, VariableScope variables, String subjectUserId) {
        if (!ProcessModelCompiler.isExpression(assigneeSpec)) {
            return assigneeSpec;
        }
        Expression expression = ProcessModelCompiler.preParse(assigneeSpec);
        Object value = evaluate(expression, variables, subjectUserId);
        return value == null ? null : String.valueOf(value);
    }

    /** 受限求值上下文（白名单装配面）。 */
    private EvaluationContext buildContext(VariableScope variables, String subjectUserId) {
        ExpressionFunctions functions = new ExpressionFunctions(
                variables == null ? java.util.Map.of() : variables.rootSummary(),
                roleCode -> identityDirectory.roleCodesOf(parseId(subjectUserId)).contains(roleCode),
                deptId -> {
                    Long userDept = identityDirectory.deptIdOf(parseId(subjectUserId));
                    return userDept != null && String.valueOf(userDept).equals(deptId);
                },
                subjectUserId);
        StandardEvaluationContext context = new StandardEvaluationContext(functions);
        // 属性访问：只读变量面（canWrite=false——变量写入唯一入口为命令内核）
        context.setPropertyAccessors(List.of(new WorkflowVariablePropertyAccessor(variables, subjectUserId)));
        // 方法解析：仅根对象实例方法（DataBindingMethodResolver 语义——禁静态/类型级方法）
        context.setMethodResolvers(List.of(
                org.springframework.expression.spel.support.DataBindingMethodResolver
                        .forInstanceMethodInvocation()));
        // 类型引用封禁（§3.5 禁类型引用）
        context.setTypeLocator(typeName -> {
            throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND, typeName);
        });
        // 构造器封禁
        context.setConstructorResolvers(List.of());
        // Bean 容器访问封禁
        context.setBeanResolver(null);
        return context;
    }

    /** 用户 id 解析（字符串主体 → Long；非数字主体返回 null——hasRole/inDept 判空失败）。 */
    private static Long parseId(String subjectUserId) {
        if (subjectUserId == null || subjectUserId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(subjectUserId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
