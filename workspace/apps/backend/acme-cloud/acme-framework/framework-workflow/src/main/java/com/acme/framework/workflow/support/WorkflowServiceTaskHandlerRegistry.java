package com.acme.framework.workflow.support;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServiceTask handler 注册表（REQ-20261122-233425 确认②①；serviceRef 路由事实源，同
 * framework-job JobHandlerRegistry 口径）：handler bean 经 ContextRefreshedEvent 收集器
 * 登记（framework-job TaskRegistryCollector 同款——登记在 web 服务器启动之后，handler
 * serviceRef 的部署形态占位符——如 RANDOM_PORT 直连地址——解析可达）；重复注册显式失败；
 * 接收端点按 payload.serviceRef 路由，未注册 → {@link WorkflowServiceTaskError#HANDLER_NOT_FOUND}
 * （404 语义明确，AC-1）。
 */
public class WorkflowServiceTaskHandlerRegistry {

    private final Map<String, WorkflowServiceTaskHandler> handlers = new ConcurrentHashMap<>();

    /** 容器装配构造（handler bean 经收集器在上下文刷新后登记）。 */
    public WorkflowServiceTaskHandlerRegistry() {
    }

    /**
     * 登记一个 handler（serviceRef 冲突显式失败，与 TaskRegistry/JobHandlerRegistry 同口径）。
     * 同实例重登幂等——ContextRefreshedEvent 会随 LB 子上下文刷新向父传播（Spring Cloud
     * LoadBalancerClientFactory 每服务名一子上下文），收集器重入时按实例身份去重。
     */
    public void register(WorkflowServiceTaskHandler handler) {
        WorkflowServiceTaskHandler existing = handlers.putIfAbsent(handler.serviceRef(), handler);
        if (existing != null && existing != handler) {
            throw new IllegalStateException("ServiceTask handler 重复注册（REQ-20261122-233425 确认②）: "
                    + handler.serviceRef());
        }
    }

    /** 测试/编程装配便利构造（逐个登记，冲突 fail-fast；容器装配经收集器走无参构造）。 */
    public WorkflowServiceTaskHandlerRegistry(Iterable<WorkflowServiceTaskHandler> handlers) {
        if (handlers != null) {
            for (WorkflowServiceTaskHandler handler : handlers) {
                register(handler);
            }
        }
    }

    /** 查询登记。 */
    public Optional<WorkflowServiceTaskHandler> find(String serviceRef) {
        return Optional.ofNullable(handlers.get(serviceRef));
    }

    /** 路由（未注册即 {@link WorkflowServiceTaskError#HANDLER_NOT_FOUND}——接收端点 404 语义）。 */
    public WorkflowServiceTaskHandler require(String serviceRef) {
        return find(serviceRef).orElseThrow(() -> new com.acme.common.core.BizException(
                WorkflowServiceTaskError.HANDLER_NOT_FOUND,
                "ServiceTask 未注册 handler：" + serviceRef));
    }

    /** 已登记 serviceRef 集（注册表视图；稳定排序）。 */
    public Set<String> serviceRefs() {
        return Set.copyOf(handlers.keySet());
    }

    /** 已登记数量。 */
    public int size() {
        return handlers.size();
    }
}
