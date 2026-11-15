package com.acme.platform.auth.interfaces;

import com.acme.common.core.Result;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.auth.api.AdminLoginLogView;
import com.acme.platform.auth.api.AdminOnlineUserView;
import com.acme.platform.auth.application.LoginLogQueryUseCase;
import com.acme.platform.auth.application.OnlineSessionUseCase;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 登录安全域管理接口（REQ-20261114-120105 §API；路径含端别前缀——网关不 StripPrefix）：
 * 登录日志分页（auth:loginlog:list）、在线用户列表（auth:online:list）与踢下线（auth:online:revoke）；
 * 业务失败=Result.code≠0（HTTP 200）：SESSION_NOT_FOUND / REVOKE_SUPERADMIN_FORBIDDEN。
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class AuthSecurityController {

    private final LoginLogQueryUseCase loginLogQueryUseCase;
    private final OnlineSessionUseCase onlineSessionUseCase;

    public AuthSecurityController(LoginLogQueryUseCase loginLogQueryUseCase,
                                  OnlineSessionUseCase onlineSessionUseCase) {
        this.loginLogQueryUseCase = loginLogQueryUseCase;
        this.onlineSessionUseCase = onlineSessionUseCase;
    }

    /**
     * 登录日志分页筛选查询（username 包含匹配 / userType / result / created_at 时间区间；
     * 时间参数 from/to=ISO-8601，契约确认记录⑦钉死命名）。
     */
    @GetMapping("/login-logs")
    public PageResult<AdminLoginLogView> loginLogs(@RequestParam(defaultValue = "1") Integer page,
                                                   @RequestParam(defaultValue = "20") Integer size,
                                                   @RequestParam(required = false) String username,
                                                   @RequestParam(required = false) String userType,
                                                   @RequestParam(required = false) String result,
                                                   @RequestParam(required = false) Instant from,
                                                   @RequestParam(required = false) Instant to) {
        return loginLogQueryUseCase.page(new PageQuery(page, size), username, userType, result,
                from, to);
    }

    /** 活跃会话列表（来源=AuthSession 未过期集合；过期会话不出现，AC-3；非分页数组）。 */
    @GetMapping("/online-users")
    public List<AdminOnlineUserView> onlineUsers() {
        return onlineSessionUseCase.listActive();
    }

    /** 踢下线（写既有撤销集立即生效，AC-4；目标不存在=SESSION_NOT_FOUND；非超管踢超管=REVOKE_SUPERADMIN_FORBIDDEN）。
     *  返回标准 Result 信封（code=0，frontend 按此解析）。 */
    @DeleteMapping("/online-users/{sessionId}")
    public Result<Void> kickOnlineUser(@PathVariable String sessionId) {
        onlineSessionUseCase.kick(sessionId);
        return Result.ok(null);
    }
}
