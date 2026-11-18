package com.acme.bootstrap.local;

import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.application.AuthorizationUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 单体本地短路适配器（REQ-20261118-002130 确认②）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 system 域 {@link AuthorizationUseCase}，行为等价于 AuthorizationController 的
 * /internal/v1/authorization/** 端点。
 * 上下文口径：透明透传——主体判定以调用方 UserContext（userId + roles claims）为事实源
 * （auth/infra 侧 AuthorizationGateway「不重绑上下文」口径，内部令牌透传同型）；
 * 共享消费方（auth 与 infra 同用本接口）不能绑定单一服务身份，透传即等价分布式线上的
 * claims 快照语义。
 */
@Component
@Primary
public class LocalAuthorizationClient implements AuthorizationClient {

    private final AuthorizationUseCase authorizationUseCase;

    public LocalAuthorizationClient(AuthorizationUseCase authorizationUseCase) {
        this.authorizationUseCase = authorizationUseCase;
    }

    /** 判定调用方角色集是否具备指定权限点（内置超管短路放行由用例承担）。 */
    @Override
    public ContractResult<Boolean> hasPermission(String code) {
        return LocalContractResults.ok(authorizationUseCase.hasPermission(code));
    }

    /** 当前主体实查内置角色编码集（roles 仅查询键，内置角色行权威；无内置角色返回空集）。 */
    @Override
    public ContractResult<Set<String>> currentBuiltinRoles() {
        return LocalContractResults.ok(authorizationUseCase.currentBuiltinRoles());
    }
}
