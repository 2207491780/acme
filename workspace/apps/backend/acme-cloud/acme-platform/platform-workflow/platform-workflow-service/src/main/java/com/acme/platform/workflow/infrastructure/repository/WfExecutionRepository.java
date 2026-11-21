package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.workflow.domain.WfExecution;

import java.util.List;

/** 执行树仓储（token 模型装载面；子树回收物理删除走 FlexDeletes 旁路——绑定表口径外，§3.2 语义）。 */
public interface WfExecutionRepository extends FlexRepository<WfExecution> {

    /** 实例全量执行树（引擎装载/详情投影）。 */
    List<WfExecution> findByInstanceId(Long instanceId);

    /** 实例内活跃执行（token 在途面）。 */
    List<WfExecution> findActiveByInstanceId(Long instanceId);

    /** 节点维度活跃执行（并行 join 到达计数锚点，§3.2）。 */
    List<WfExecution> findActiveByInstanceIdAndNodeId(Long instanceId, String nodeId);

    /**
     * 节点维度活跃执行（FOR UPDATE 行锁面——并行 join 并发到达的计数串行化：后到命令阻塞于
     * 前到命令的行锁直至其提交，杜绝双双读到旧计数致聚合永不触发；BPM-1 round-1 修复⑦）。
     */
    List<WfExecution> findActiveByInstanceIdAndNodeIdForUpdate(Long instanceId, String nodeId);

    /** 直接子执行（fork 派生/子流程 scope 根定位）。 */
    List<WfExecution> findByParentId(Long parentId);

    /**
     * 按 PK 全局直读（ServiceTask 回调/重试通道旁路声明——12.4.2）：回调方内部令牌可能不携
     * 行租户上下文，行租户为权威（装载后以行租户绑定命令线程，后续查询恢复租户过滤面）。
     */
    java.util.Optional<WfExecution> findByIdGlobal(Long id);
}
