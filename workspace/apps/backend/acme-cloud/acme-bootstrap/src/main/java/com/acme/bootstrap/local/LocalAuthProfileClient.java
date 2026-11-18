package com.acme.bootstrap.local;

import com.acme.platform.system.api.AuthProfileClient;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.application.AuthProfileUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单体本地短路适配器（REQ-20261118-002130 确认②）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} 令跨域注入点取本类，Feign 工厂 bean 因 lazy-attributes-resolution 惰性注册
 * 永不实例化）。直调 system 域 {@link AuthProfileUseCase}，行为等价于
 * {@code AuthProfileController} 的 /internal/v1/auth-profiles 端点（Result.ok 信封同构）。
 * 上下文口径：透明透传调用方 UserContext（内部令牌透传同型——auth 侧网关已绑服务身份
 * platform-auth，无需重绑，见 TenantSessionRevocationGateway 同款先例）。
 */
@Component
@Primary
public class LocalAuthProfileClient implements AuthProfileClient {

    private final AuthProfileUseCase authProfileUseCase;

    public LocalAuthProfileClient(AuthProfileUseCase authProfileUseCase) {
        this.authProfileUseCase = authProfileUseCase;
    }

    /** 按用户名查认证画像（查无此用户 → 用例显式失败，消费方按 USER_NOT_FOUND 统一映射 LOGIN_FAILED）。 */
    @Override
    public ContractResult<AuthProfileView> findByUsername(String username) {
        return LocalContractResults.ok(authProfileUseCase.findByUsername(username));
    }

    /** 按 userId 批量查认证画像（不存在的 userId 不在结果中，消费方按缺省展示处理）。 */
    @Override
    public ContractResult<List<AuthProfileView>> findByUserIds(List<Long> userIds) {
        return LocalContractResults.ok(authProfileUseCase.findByUserIds(userIds));
    }
}
