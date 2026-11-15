package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.api.DataScopeView;
import com.acme.platform.system.application.AuthorizationUseCase;
import com.acme.platform.system.security.DataScopeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间权限判定内部接口（FR-008 跨域执行面；调用方角色集取内部令牌 claims）。
 * REQ-20261114-120104 增补 data-scope 解析端点：五档数据范围 + 可见部门/用户集（审计示范域消费）。
 */
@RestController
public class AuthorizationController {

    private final AuthorizationUseCase authorizationUseCase;
    private final DataScopeService dataScopeService;

    public AuthorizationController(AuthorizationUseCase authorizationUseCase, DataScopeService dataScopeService) {
        this.authorizationUseCase = authorizationUseCase;
        this.dataScopeService = dataScopeService;
    }

    @GetMapping("/internal/v1/authorization/permissions/{code}")
    public Result<Boolean> hasPermission(@PathVariable("code") String code) {
        return Result.ok(authorizationUseCase.hasPermission(code));
    }

    /** 当前主体数据范围（走库解析：角色档最宽 + 部门子树展开 + 部门→用户集）。 */
    @GetMapping("/internal/v1/authorization/data-scope")
    public Result<DataScopeView> currentDataScope() {
        var decision = dataScopeService.currentSubjectWithUserIds();
        return Result.ok(new DataScopeView(decision.scope().name(),
                List.copyOf(decision.visibleDeptIds()), List.copyOf(decision.visibleUserIds())));
    }
}
