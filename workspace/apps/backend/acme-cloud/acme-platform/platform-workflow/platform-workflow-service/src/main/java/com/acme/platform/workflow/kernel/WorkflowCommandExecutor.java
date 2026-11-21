package com.acme.platform.workflow.kernel;

import com.acme.common.core.Clock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 命令执行器（设计规格 §3.1 唯一入口）：责任链组装。拦截器序经 @Order 排序：
 * ContextInit(10)→OptimisticLockRetry(20)→Load(30)→Validation(40)→Execution(50)→
 * Persistence(60)→EventPublish(70)；单命令单事务 + 乐观锁重试环由 OptimisticLockRetry
 * 拦截器承载（Load~EventPublish 为每轮尝试的独立事务——STALE 回滚本轮整体重跑，
 * 杜绝「同事务内重试」的部分写残留；确认③链形与 AC-12 语义见拦截器类注）。
 * 只读查询不走命令链（直接 service 查询，§3.1）。
 */
@Component
public class WorkflowCommandExecutor {

    private final Clock clock;
    private final List<CommandInterceptor> chain;

    public WorkflowCommandExecutor(Clock workflowClock, List<CommandInterceptor> interceptors) {
        this.clock = workflowClock;
        // Spring 依赖注入按 @Order 排序（10/20/.../70），链序即为确认③责任链终稿
        this.chain = List.copyOf(interceptors);
    }

    /**
     * 执行命令（返回命令 execute 段结果；事务边界在 Retry 拦截器内）。
     * 命令为有状态单例（load/validate/execute 段字段承载本轮装载态），按命令实例串行化：
     * 并发同型命令在锁上排队，各自整链独占执行——并发 claim/complete 收敛单胜（AC-7），
     * 杜绝跨线程字段互踩（AC-7 竞态实证：胜者信封与库内 assignee 错位）。
     */
    @SuppressWarnings("unchecked")
    public <R> R execute(WorkflowCommand<R> command, Object payload) {
        CommandContext context = new CommandContext(command, payload, clock.now());
        Object result;
        synchronized (command) {
            result = invokeAt(0, context);
        }
        return (R) result;
    }

    private Object invokeAt(int index, CommandContext context) {
        if (index >= chain.size()) {
            return context.result();
        }
        CommandInterceptor interceptor = chain.get(index);
        return interceptor.intercept(new CommandInvocation() {
            @Override
            public CommandContext context() {
                return context;
            }

            @Override
            public Object proceed() {
                return invokeAt(index + 1, context);
            }
        });
    }
}
