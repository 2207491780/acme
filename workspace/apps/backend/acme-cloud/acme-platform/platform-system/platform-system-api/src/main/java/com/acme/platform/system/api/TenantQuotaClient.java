package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 租户配额解析契约（REQ-20261122-150802 确认③）：platform-infra 文件上传配额校验（FileUseCase.
 * checkQuota 收编）数据源，以服务身份主体调用（Q-D08，同 TenantClient 口径）；三级覆盖链
 * （租户覆盖→套餐→平台缺省）解析在提供方完成。未知租户由服务端返回 404 错误信封
 * （TENANT_NOT_FOUND），不经本类型解码（消费方降级口径由调用方承担）。
 */
@FeignClient(name = "platform-system", contextId = "tenantQuotaClient")
public interface TenantQuotaClient {

    /**
     * 按租户代码解析有效存储配额（三级覆盖链；确认③）。
     *
     * @param tenantCode 租户代码（全局唯一键；文件上传恒在租户上下文内解析同租户）
     * @return 信封携带解析后配额值与来源层级
     */
    @GetMapping("/internal/v1/tenants/quota")
    ContractResult<TenantQuotaView> resolveQuota(@RequestParam("tenantCode") String tenantCode);
}
