package com.acme.platform.workflow.application;

import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.engine.VariableScope;

import java.util.function.Supplier;

/**
 * 变量持久化汇（CommandContext 持久化队列适配）：变量写/回收登记为命令队列操作，
 * Persistence 拦截器位同事务落库（AC-12 单命令单事务）；实例 id/租户经 Supplier 惰性解析
 * （雪花 id 落库时分配——upsert 在 Persistence 段执行时取值非空）。
 */
public final class VariableSinkAdapter implements VariableScope.PersistenceSink {

    private final VariableService variableService;
    private final CommandContext commandContext;
    private final Supplier<Long> instanceIdSupplier;
    private final Supplier<String> tenantIdSupplier;

    public VariableSinkAdapter(VariableService variableService, CommandContext commandContext,
                               Supplier<Long> instanceIdSupplier, Supplier<String> tenantIdSupplier) {
        this.variableService = variableService;
        this.commandContext = commandContext;
        this.instanceIdSupplier = instanceIdSupplier;
        this.tenantIdSupplier = tenantIdSupplier;
    }

    @Override
    public void saveVariable(String name, Object value) {
        commandContext.persist(() -> variableService.upsert(instanceIdSupplier.get(), name, value,
                tenantIdSupplier.get()));
    }

    @Override
    public void deleteVariablesByPrefix(String prefix) {
        Long instanceId = instanceIdSupplier.get();
        commandContext.persist(() -> variableService.deleteByPrefix(instanceId, prefix));
    }
}
