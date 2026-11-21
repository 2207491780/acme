package com.acme.platform.workflow.expression;

import com.acme.platform.workflow.engine.VariableScope;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

/**
 * 变量属性访问器（受限求值上下文装配面）：SpEL 裸属性名（如 {@code amount > 100} 的 amount）
 * 解析到流程变量（读穿透语义随 VariableScope）；{@code initiator} 属性解析到求值主体。
 * 只读（canWrite=false——变量写入唯一入口为命令内核，表达式侧禁写，§3.5 安全红线）。
 */
public final class WorkflowVariablePropertyAccessor implements PropertyAccessor {

    private final VariableScope variables;
    private final String subjectUserId;

    public WorkflowVariablePropertyAccessor(VariableScope variables, String subjectUserId) {
        this.variables = variables;
        this.subjectUserId = subjectUserId;
    }

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        // 全目标面（根对象=ExpressionFunctions 函数白名单；变量读取只经 VariableScope 作用域视图）
        return null;
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return true;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) {
        if ("initiator".equals(name)) {
            return new TypedValue(subjectUserId);
        }
        return new TypedValue(variables.get(name));
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) {
        throw new IllegalStateException("流程变量禁止经表达式写入（§3.5 安全红线）");
    }
}
