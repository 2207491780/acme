package com.acme.platform.auth.interfaces;

import com.acme.common.core.BizException;
import com.acme.common.core.Result;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.audit.support.AuditLog;
import com.acme.framework.excel.internal.ExcelHttpExporter;
import com.acme.framework.web.support.WebError;
import com.acme.platform.auth.api.AdminLoginLogView;
import com.acme.platform.auth.api.AdminOnlineUserView;
import com.acme.platform.auth.api.BatchKickView;
import com.acme.platform.auth.application.ExportResult;
import com.acme.platform.auth.application.LoginLogExportRow;
import com.acme.platform.auth.application.LoginLogQueryUseCase;
import com.acme.platform.auth.application.OnlineSessionUseCase;
import com.acme.platform.auth.domain.SysLoginLog;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
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
 * 登录日志分页（auth:loginlog:list）与导出（REQ-20261117-061017 确认④，权限同 list）、
 * 在线用户列表（auth:online:list）、踢下线（auth:online:revoke）与按用户名批量下线
 * （REQ-20261117-061017 确认⑥）；
 * 业务失败=Result.code≠0（HTTP 200）：SESSION_NOT_FOUND / REVOKE_SUPERADMIN_FORBIDDEN。
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class AuthSecurityController {

    private static final String EXPORT_FILE_NAME = "login-logs.xlsx";

    private static final String EXPORT_PART_SUFFIX = ".part";

    private final LoginLogQueryUseCase loginLogQueryUseCase;
    private final OnlineSessionUseCase onlineSessionUseCase;
    private final ExcelHttpExporter excelHttpExporter;

    public AuthSecurityController(LoginLogQueryUseCase loginLogQueryUseCase,
                                  OnlineSessionUseCase onlineSessionUseCase,
                                  ExcelHttpExporter excelHttpExporter) {
        this.loginLogQueryUseCase = loginLogQueryUseCase;
        this.onlineSessionUseCase = onlineSessionUseCase;
        this.excelHttpExporter = excelHttpExporter;
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

    /**
     * 登录日志同步导出（REQ-20261117-061017 确认④：xlsx 附件豁免信封，权限判定在用例内同 list；
     * 上限 10000 行——超限截断并响应 X-Export-Truncated: true，文件名 .part 后缀）。
     */
    @GetMapping("/login-logs/export")
    public void loginLogsExport(HttpServletResponse response,
                                @RequestParam(required = false) String username,
                                @RequestParam(required = false) String userType,
                                @RequestParam(required = false) String result,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to) {
        ExportResult<SysLoginLog> batch =
                loginLogQueryUseCase.export(username, userType, result, from, to);
        List<LoginLogExportRow> rows = batch.records().stream().map(LoginLogExportRow::from).toList();
        if (batch.truncated()) {
            response.setHeader("X-Export-Truncated", "true");
        }
        excelHttpExporter.export(response,
                batch.truncated() ? EXPORT_FILE_NAME + EXPORT_PART_SUFFIX : EXPORT_FILE_NAME,
                rows, LoginLogExportRow.class);
    }

    /** 活跃会话列表（来源=AuthSession 未过期集合；过期会话不出现，AC-3；非分页数组）。 */
    @GetMapping("/online-users")
    public List<AdminOnlineUserView> onlineUsers() {
        return onlineSessionUseCase.listActive();
    }

    /**
     * 按用户名批量下线（REQ-20261117-061017 确认⑥：枚举该用户全部 ACTIVE 未过期会话逐个 kick，
     * 超管会话跳过并计数；返回 kickedCount/skippedCount；写动作纳入审计）。
     */
    @DeleteMapping("/online-users")
    @AuditLog(action = "auth.online.batchKick", targetType = "onlineUser", contentParams = {"username"})
    public Result<BatchKickView> kickOnlineUsersByUsername(@RequestParam @NotBlank String username) {
        if (username.isBlank()) {
            // 缺参由框架 400；显式空白同口径（captcha-policy 参数校验先例）
            throw new BizException(WebError.VALIDATION_FAILED);
        }
        return Result.ok(onlineSessionUseCase.kickAllByUsername(username.trim()));
    }

    /** 踢下线（写既有撤销集立即生效，AC-4；目标不存在=SESSION_NOT_FOUND；非超管踢超管=REVOKE_SUPERADMIN_FORBIDDEN）。
     *  返回标准 Result 信封（code=0，frontend 按此解析）；写动作纳入审计。 */
    @DeleteMapping("/online-users/{sessionId}")
    @AuditLog(action = "auth.online.kick", targetType = "onlineUser", contentParams = {"sessionId"})
    public Result<Void> kickOnlineUser(@PathVariable String sessionId) {
        onlineSessionUseCase.kick(sessionId);
        return Result.ok(null);
    }
}
