package com.acme.platform.system.security;

import com.acme.framework.web.security.PermissionDecider;
import com.acme.framework.web.security.RequirePermissionInterceptorSupport;
import org.springframework.stereotype.Component;

/**
 * 接口级权限拦截器（system 域本地判定特化，架构设计 5.2）：解析 {@code @RequirePermission}，
 * 交由 {@code PermissionDecisionService} 本地对照判定（无远程调用，M-05 安全；未命中抛
 * PERMISSION_DENIED 403。超管能力来自种子对内置 super_admin 的全量权限点绑定，
 * 无编码短路——角色编码不是信任锚，review 修复 P0）。
 * 判定骨架上提 framework-web（REQ-20261127-161433 P2-19：域内保留本地判定特化策略）。
 * 显式 bean 名（REQ-20261118-002130）：与 infra 域同名类在单体裁装下消歧，纯命名注解无行为变化。
 */
@Component("systemRequirePermissionInterceptor")
public class RequirePermissionInterceptor extends RequirePermissionInterceptorSupport {

    public RequirePermissionInterceptor(PermissionDecisionService decisionService) {
        super(decisionService::checkCurrentSubject);
    }
}
