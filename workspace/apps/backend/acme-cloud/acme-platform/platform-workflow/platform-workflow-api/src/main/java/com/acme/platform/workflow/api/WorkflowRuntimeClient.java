package com.acme.platform.workflow.api;

import com.acme.platform.system.api.ContractResult;
import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import com.acme.platform.workflow.api.dto.WorkflowVariablesWriteRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 流程运行时跨服务契约（REQ-20261122-233425 确认②③；业务服务经 SDK/Feign 消费 workflow
 * internal 面，对齐 AuthorizationClient/TenantQuotaClient 惯例：接口放目标服务 *-api 模块，
 * 业务服务经 @EnableFeignClients(clients=…) 显式登记，X-Acme-Token 由 framework-feign 出站
 * 拦截器签发——sub=业务服务主体，internal 面不经网关直连）。
 *
 * <p>协议终稿（与 platform-workflow-service internal 端点逐字一致——AC-9 消费侧冻结，
 * 单测锁定路径/方法/形参注解）：
 * <ul>
 *   <li>POST /internal/v1/workflow/process-instances → 启动实例（definitionId+businessKey+variables
 *       +initiatorUserId，业务身份 sub 透传审计），返回 instanceId</li>
 *   <li>GET /internal/v1/workflow/process-instances/{id} → 运行时详情（实例+变量 Map+活动任务
 *       快照 TaskView 列表）</li>
 *   <li>PUT /internal/v1/workflow/process-instances/{id}/variables → 变量增量合并写
 *       （uk(instance_id,name) upsert，复用 VariableService）</li>
 * </ul>
 * 系统侧任务推进（complete by system）不在本期（BPM-6 确认项）。错误信封经 framework-feign
 * 解码为 BizException（internal 口径 401/403/404/409 随错误码段）。</p>
 */
@FeignClient(name = "platform-workflow", contextId = "workflowRuntimeClient")
public interface WorkflowRuntimeClient {

    /** 启动流程实例（businessKey 必填=业务对账锚点；initiatorUserId=业务发起人用户 id）。 */
    @PostMapping("/internal/v1/workflow/process-instances")
    ContractResult<Long> startProcessInstance(@RequestBody InternalProcessInstanceStartRequest request);

    /** 运行时详情（实例 + 变量 Map + 活动任务快照 List<TaskView>；未知 id → 404 信封，不经本类型解码）。 */
    @GetMapping("/internal/v1/workflow/process-instances/{id}")
    ContractResult<WorkflowInstanceDetailView> processInstanceDetail(@PathVariable("id") Long id);

    /** 变量增量合并写（请求 variables 键值并入实例变量，可空 Map 忽略）。 */
    @PutMapping("/internal/v1/workflow/process-instances/{id}/variables")
    ContractResult<Void> writeVariables(@PathVariable("id") Long id,
                                        @RequestBody WorkflowVariablesWriteRequest request);
}
