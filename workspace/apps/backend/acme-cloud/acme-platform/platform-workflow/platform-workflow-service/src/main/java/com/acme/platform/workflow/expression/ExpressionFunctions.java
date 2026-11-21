package com.acme.platform.workflow.expression;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * SpEL 内置函数根对象（SimpleEvaluationContext forReadOnlyDataBinding 装配面——设计规格 §3.5
 * 白名单终稿：var(name)/hasRole(code)/inDept(id)/initiator 常量，禁类型引用/Bean 访问）。
 * 根对象继承 Map（Spring ReflectivePropertyAccessor 的 Map 语义：裸属性名 {@code amount} 直接
 * 命中变量键——标准 BPMN ${…} 表达式形态兼容）；方法面（hasRole/inDept/var/initiator）经
 * DataBindingMethodResolver 实例方法解析命中本类（SimpleEvaluationContext 预设安全面：
 * 只读属性 + 实例方法、禁写入/类型引用/构造器）。
 * hasRole/inDept 经 {@link WorkflowExpressionEvaluator} 注入的身份目录解析（流程引擎本地快照
 * 方案，REQ-20261121-142316 确认④配套）。
 */
public final class ExpressionFunctions extends LinkedHashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    private final Predicate<String> roleChecker;
    private final Predicate<String> deptChecker;
    private final String initiator;

    public ExpressionFunctions(Map<String, Object> variables,
                               Predicate<String> roleChecker,
                               Predicate<String> deptChecker,
                               String initiator) {
        super(variables == null ? Map.of() : variables);
        this.roleChecker = roleChecker;
        this.deptChecker = deptChecker;
        this.initiator = initiator;
        // initiator 常量以键形态进 Map（裸属性 initiator 直取；方法形态 initiator() 同值）
        put("initiator", initiator);
    }

    /** 取流程变量（缺失返回 null）。 */
    public Object var(String name) {
        return name == null ? null : get(name);
    }

    /** 当前主体是否具备角色编码。 */
    public boolean hasRole(String roleCode) {
        return roleCode != null && roleChecker.test(roleCode.trim());
    }

    /** 当前主体是否归属部门 id。 */
    public boolean inDept(String deptId) {
        return deptId != null && deptChecker.test(deptId.trim());
    }

    /** 发起人常量（方法形态）。 */
    public String initiator() {
        return initiator;
    }
}
