package com.acme.platform.auth.interfaces;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.Result;
import com.acme.framework.audit.support.AuditLog;
import com.acme.framework.security.support.SecurityError;
import com.acme.platform.auth.api.SelfSessionView;
import com.acme.platform.auth.application.SelfAccountUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 自助账号安全接口（REQ-20261119-082531 确认记录③④；路径含端别前缀——网关不 StripPrefix）：
 * 自助改密（PUT password——成功返回 Result&lt;Void&gt; 信封且当前会话保持）、本人在线会话列表
 * （GET sessions）、下线本人指定会话（DELETE sessions/{sessionId}）、下线本人其他会话
 * （DELETE sessions/others）。全部免权限点（认证即可，无 @RequirePermission 新权限点）——
 * 「仅本人」语义由用例按 UserContext 主体收敛。
 * 身份与当前会话标识：userId=UserContext（InternalTokenAuthFilter 绑定）；当前 tokenId=
 * Authorization Bearer 用户访问令牌自校验（网关原样透传该头，AuthTokenController 登出/续约同款
 * 先例——网关注入的内部令牌 X-Acme-Token jti 是逐请求新签值，与会话 tokenId 无关）。
 * wire 口径（frontend core api 对齐）：PUT password 与 GET sessions 出 Result 信封
 * （StandardResponseAdvice 统一包装）；两个 DELETE 为 void 端点（2xx 空体=成功，Modules.md 3.1.1）。
 */
@RestController
@RequestMapping("/admin/v1/auth/me")
public class SelfAccountController {

    private final SelfAccountUseCase selfAccountUseCase;

    public SelfAccountController(SelfAccountUseCase selfAccountUseCase) {
        this.selfAccountUseCase = selfAccountUseCase;
    }

    /**
     * 自助改密（确认记录③）：旧密码错误 OLD_PASSWORD_INVALID / 新密码违反 8-64 策略
     * PASSWORD_POLICY_VIOLATED / 新旧相同 SAME_PASSWORD；成功后其他会话 KICKED+撤销、
     * 当前会话保持（响应头令牌不变）。写动作纳入审计。
     */
    @PutMapping("/password")
    @AuditLog(action = "auth.self.changePassword", targetType = "selfAccount")
    public Result<Void> changePassword(@Valid @RequestBody SelfPasswordChangeRequest request,
                                       @RequestHeader(value = "Authorization", required = false)
                                       String authorization) {
        selfAccountUseCase.changePassword(requireUserId(), stripBearer(authorization),
                request.oldPassword(), request.newPassword());
        return Result.ok(null);
    }

    /** 本人在线会话列表（确认记录④）：tokenId/ip/issuedAt/lastActiveAt + current 标记；非分页数组。 */
    @GetMapping("/sessions")
    public List<SelfSessionView> sessions(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return selfAccountUseCase.listMySessions(requireUserId(), stripBearer(authorization));
    }

    /**
     * 下线本人指定会话（确认记录④）：仅本人会话可删，非本人/不存在统一 SESSION_NOT_FOUND
     * （NOT_FOUND 语义防探测）；幂等终态（已登出/已踢会话同语义）。void 端点（2xx 空体=成功）。
     */
    @DeleteMapping("/sessions/{sessionId}")
    @AuditLog(action = "auth.self.kickSession", targetType = "selfSession",
            contentParams = {"sessionId"})
    public void kickSession(@PathVariable String sessionId) {
        selfAccountUseCase.kickMySession(requireUserId(), sessionId);
    }

    /** 下线本人其他会话（确认记录④）：保留当前会话；无其他活跃会话幂等成功。void 端点。 */
    @DeleteMapping("/sessions/others")
    @AuditLog(action = "auth.self.kickOtherSessions", targetType = "selfSession")
    public void kickOtherSessions(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        selfAccountUseCase.kickMyOtherSessions(requireUserId(), stripBearer(authorization));
    }

    /** 当前主体解析（UserContext 为网关验签事实；未认证请求统一 401，不落入用例层）。 */
    private static Long requireUserId() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new BizException(SecurityError.UNAUTHORIZED);
        }
        return Long.valueOf(context.userId());
    }

    /** Bearer 前缀剥离（AuthTokenController 同型口径）。 */
    private static String stripBearer(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value;
    }
}
