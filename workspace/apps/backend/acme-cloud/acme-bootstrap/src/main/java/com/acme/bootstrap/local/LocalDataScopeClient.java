package com.acme.bootstrap.local;

import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.DataScopeClient;
import com.acme.platform.system.api.DataScopeView;
import com.acme.platform.system.security.DataScopeService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单体本地短路适配器（REQ-20261118-002130 确认②）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 system 域 {@link DataScopeService}（AuthorizationController 同型组装：
 * 五档 + 可见部门/用户集），行为等价 /internal/v1/authorization/data-scope 端点。
 * 上下文口径：透明透传——主体取调用方 UserContext（infra 侧 DataScopeGateway 走当前主体
 * 解析，内部令牌透传同型）。
 */
@Component
@Primary
public class LocalDataScopeClient implements DataScopeClient {

    private final DataScopeService dataScopeService;

    public LocalDataScopeClient(DataScopeService dataScopeService) {
        this.dataScopeService = dataScopeService;
    }

    /** 解析当前调用主体的数据范围（五档 + 可见部门/用户集；无角色可解析时按 SELF fail-closed）。 */
    @Override
    public ContractResult<DataScopeView> currentDataScope() {
        var decision = dataScopeService.currentSubjectWithUserIds();
        return LocalContractResults.ok(new DataScopeView(decision.scope().name(),
                List.copyOf(decision.visibleDeptIds()), List.copyOf(decision.visibleUserIds())));
    }
}
