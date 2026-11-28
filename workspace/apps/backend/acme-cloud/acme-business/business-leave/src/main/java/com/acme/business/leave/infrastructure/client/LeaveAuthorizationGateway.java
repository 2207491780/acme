package com.acme.business.leave.infrastructure.client;

import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.ContractResult;
import org.springframework.stereotype.Component;

/**
 * 跨域权限判定网关（leave 域详情越权收口的授权判定面，REQ-20261127-235808 决策③；
 * 与 auth/infra/scheduler/workflow 四域 AuthorizationGateway 同先例）：调用方主体
 * （网关注入的内部令牌 claims → UserContext）经 platform-system 实查判定；roles 仅查询键、
 * 不作信任锚（无编码短路放行）。单体经 LocalAuthorizationClient（@Primary）本地短路，
 * 分布式由 Feign 承载。
 * 显式 bean 名（REQ-20261118-002130 消歧惯例）：与各域同名网关类在单体裁装下消歧。
 * fail-closed：判定不可达/异常按无权限处理（不抛，调用方组合判定）——leave 无 all-or-nothing
 * 端点门，故不提供 requirePermission。
 */
@Component("leaveAuthorizationGateway")
public class LeaveAuthorizationGateway {

    private final AuthorizationClient authorizationClient;

    public LeaveAuthorizationGateway(AuthorizationClient authorizationClient) {
        this.authorizationClient = authorizationClient;
    }

    /** 判定当前主体是否具备权限点（不抛；判定服务异常按无权限处理，fail-closed）。 */
    public boolean hasPermission(String permissionCode) {
        try {
            ContractResult<Boolean> result = authorizationClient.hasPermission(permissionCode);
            return result != null && Boolean.TRUE.equals(result.data());
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
