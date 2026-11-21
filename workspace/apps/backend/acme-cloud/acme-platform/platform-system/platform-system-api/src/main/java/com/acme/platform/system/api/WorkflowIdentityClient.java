package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Set;

/**
 * 服务间用户身份目录契约（REQ-20261121-142316 确认④⑦配套：BPM 引擎 UserTask 候选解析与
 * SpEL 内置函数 hasRole/inDept 的数据源）。role/dept 数据归 platform-system 独占（M-02/M-03），
 * platform-workflow 跨服务取数仅经本契约（对齐 AuthorizationClient 惯例：调用方主体身份取内部
 * 令牌 claims，目录查询按显式 userId 维度、tenantId 由引擎侧上下文随请求透传，服务端按租户过滤）。
 *
 * <p>协议终稿（BPM-1 钉死，BPM-6 集成消费）：
 * <ul>
 *   <li>GET /internal/v1/workflow-identities/users/{userId}/role-codes → 用户角色编码集（无效用户=空集）</li>
 *   <li>GET /internal/v1/workflow-identities/users/{userId}/dept-id → 用户主属部门 id（未归属=null）</li>
 *   <li>GET /internal/v1/workflow-identities/roles/{roleCode}/user-ids → 按角色编码取启用用户 id 集</li>
 *   <li>GET /internal/v1/workflow-identities/depts/{deptId}/user-ids → 按部门 id 取用户 id 集</li>
 * </ul>
 * 未知编码/部门返回空集（不抛 404，目录查询为宽表语义）；失败按无权限/无候选 fail-closed。</p>
 */
@FeignClient(name = "platform-system", contextId = "workflowIdentityClient")
public interface WorkflowIdentityClient {

    /** 用户角色编码集（无效用户/无绑定返回空集）。 */
    @GetMapping("/internal/v1/workflow-identities/users/{userId}/role-codes")
    ContractResult<Set<String>> userRoleCodes(@PathVariable("userId") Long userId);

    /** 用户主属部门 id（未归属返回 null 数据）。 */
    @GetMapping("/internal/v1/workflow-identities/users/{userId}/dept-id")
    ContractResult<Long> userDeptId(@PathVariable("userId") Long userId);

    /** 按角色编码取启用用户 id 集（未知编码返回空集）。 */
    @GetMapping("/internal/v1/workflow-identities/roles/{roleCode}/user-ids")
    ContractResult<Set<Long>> roleUserIds(@PathVariable("roleCode") String roleCode);

    /** 按部门 id 取用户 id 集（未知部门返回空集）。 */
    @GetMapping("/internal/v1/workflow-identities/depts/{deptId}/user-ids")
    ContractResult<Set<Long>> deptUserIds(@PathVariable("deptId") Long deptId);
}
