package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 服务间数据范围解析契约（REQ-20261114-120104，FR-008 跨域执行面）：调用方主体取内部令牌 claims
 * （framework-security 校验后写入 UserContext），服务端走库解析五档数据范围
 * （角色 data_scope 最宽档 + 部门子树展开 + 部门→用户集解析）。
 */
@FeignClient(name = "platform-system", contextId = "dataScopeClient")
public interface DataScopeClient {

    /**
     * 解析当前调用主体的数据范围（五档 + 可见部门/用户集）。
     *
     * @return 信封携带 {@link DataScopeView}（无角色可解析时按 SELF fail-closed）
     */
    @GetMapping("/internal/v1/authorization/data-scope")
    ContractResult<DataScopeView> currentDataScope();
}
