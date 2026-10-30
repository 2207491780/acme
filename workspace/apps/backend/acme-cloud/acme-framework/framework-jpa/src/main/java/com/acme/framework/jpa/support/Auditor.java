package com.acme.framework.jpa.support;

/**
 * 操作人解析扩展点（FR-008；AuditorAware 同位语义，6.2）：
 * 审计填充取当前操作人，缺失回退 {@code acme.jpa.audit.system-operator}（缺省 {@code system}）。
 * 业务可注册自定义实现覆盖缺省实现（框架缺省读 UserContext，FR-008）。
 */
public interface Auditor {

    /** 当前操作人标识；返回 null 表示无操作人（调用方按缺省口径处理）。 */
    String currentOperator();
}
