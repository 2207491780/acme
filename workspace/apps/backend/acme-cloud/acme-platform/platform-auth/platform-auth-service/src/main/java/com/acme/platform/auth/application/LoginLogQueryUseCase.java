package com.acme.platform.auth.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.auth.api.AdminLoginLogView;
import com.acme.platform.auth.domain.SysLoginLog;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.repository.LoginLogRepository;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 登录日志查询用例（REQ-20261114-120105 AC-2）：分页 + username/userType/result/时间区间筛选；
 * 权限点 auth:loginlog:list（经 system 判定，越权 403 PERMISSION_DENIED）。
 */
@Service
public class LoginLogQueryUseCase {

    private final AuthorizationGateway authorizationGateway;
    private final LoginLogRepository loginLogRepository;

    public LoginLogQueryUseCase(AuthorizationGateway authorizationGateway, LoginLogRepository loginLogRepository) {
        this.authorizationGateway = authorizationGateway;
        this.loginLogRepository = loginLogRepository;
    }

    /**
     * 分页筛选查询（username 包含匹配，其余精确；时间区间按 created_at 双闭区间；
     * from/to=契约确认记录⑦钉死的参数命名）。
     *
     * @param query    分页协议基元
     * @param username 用户名筛选（可空）
     * @param userType 端别筛选（B/C；可空）
     * @param result   结果筛选（SUCCESS/FAILURE；可空）
     * @param from     时间区间下界（可空）
     * @param to       时间区间上界（可空）
     * @return 登录日志分页视图
     */
    public PageResult<AdminLoginLogView> page(PageQuery query, String username, String userType,
                                              String result, Instant from, Instant to) {
        authorizationGateway.requirePermission(SystemPermissions.AUTH_LOGIN_LOG_LIST);
        PageResult<SysLoginLog> page = loginLogRepository.page(query.page(), query.size(),
                username, userType, result, from, to);
        return new PageResult<>(page.records().stream().map(LoginLogQueryUseCase::toView).toList(),
                page.total());
    }

    private static AdminLoginLogView toView(SysLoginLog entity) {
        return new AdminLoginLogView(String.valueOf(entity.getId()), entity.getUsername(), entity.getUserType(),
                entity.getIp(), entity.getUserAgent(), entity.getResult(), entity.getFailReason(),
                entity.getTraceId(), entity.getCreatedAt());
    }
}
