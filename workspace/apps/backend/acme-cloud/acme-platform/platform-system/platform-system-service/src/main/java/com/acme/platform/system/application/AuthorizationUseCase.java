package com.acme.platform.system.application;

import com.acme.platform.system.security.PermissionDecisionService;
import org.springframework.stereotype.Service;

/**
 * 服务间权限判定用例（FR-008 跨域执行面）：调用方角色集取内部令牌 claims
 * （framework-security 校验后写入 UserContext）；本地判定无远程调用（M-05）。
 */
@Service
public class AuthorizationUseCase {

    private final PermissionDecisionService decisionService;

    public AuthorizationUseCase(PermissionDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /** 当前调用主体是否具备权限点。 */
    public boolean hasPermission(String permissionCode) {
        return decisionService.currentSubjectHas(permissionCode);
    }
}
