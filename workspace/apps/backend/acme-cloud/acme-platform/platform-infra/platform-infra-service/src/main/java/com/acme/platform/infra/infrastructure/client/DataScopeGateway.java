package com.acme.platform.infra.infrastructure.client;

import com.acme.platform.system.api.DataScopeClient;
import com.acme.platform.system.api.DataScopeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 跨域数据范围解析网关（REQ-20261114-120104；AuthorizationGateway 同先例）：
 * 调用方主体（内部令牌 claims → UserContext）经 platform-system 走库解析五档数据范围；
 * 判定服务异常按 null 返回（fail-closed，由调用方收敛为 SELF 档）。
 */
@Component
public class DataScopeGateway {

    private static final Logger log = LoggerFactory.getLogger(DataScopeGateway.class);

    private final DataScopeClient dataScopeClient;

    public DataScopeGateway(DataScopeClient dataScopeClient) {
        this.dataScopeClient = dataScopeClient;
    }

    /**
     * 解析当前主体数据范围（不抛；判定服务异常/非成功信封按 null 处理，fail-closed）。
     */
    public DataScopeView currentDataScope() {
        try {
            var result = dataScopeClient.currentDataScope();
            return result != null && Boolean.TRUE.equals(result.success()) ? result.data() : null;
        } catch (RuntimeException ex) {
            // fail-closed：远端不可达收敛 SELF 档（与审计原「无权限点 → 本人视图」一致），留 WARN 便于定位
            log.warn("数据范围解析异常，按 SELF 档收敛（fail-closed）", ex);
            return null;
        }
    }
}
