package com.acme.platform.auth.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.platform.auth.api.AdminLoginLogView;
import com.acme.platform.auth.api.LoginLogStatsPoint;
import com.acme.platform.auth.domain.SysLoginLog;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.repository.LoginLogRepository;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 登录日志查询用例（REQ-20261114-120105 AC-2）：分页 + username/userType/result/时间区间筛选；
 * 权限点 auth:loginlog:list（经 system 判定，越权 403 PERMISSION_DENIED）。
 * REQ-20261117-061017 确认④：同步导出（上限 10000 行并受 acme.excel.max-rows 收窄，超限截断），
 * 权限判定位置与 page 同径。
 * REQ-20261119-011524 确认记录②：按日统计（GET /internal/v1/login-log-stats 数据源，免权限点内部口径）。
 */
@Service
public class LoginLogQueryUseCase {

    /** 导出上限（确认④：10000 行）。 */
    static final long EXPORT_MAX_ROWS = 10_000L;

    private final AuthorizationGateway authorizationGateway;
    private final LoginLogRepository loginLogRepository;
    private final ExcelProperties excelProperties;

    public LoginLogQueryUseCase(AuthorizationGateway authorizationGateway, LoginLogRepository loginLogRepository,
                                ExcelProperties excelProperties) {
        this.authorizationGateway = authorizationGateway;
        this.loginLogRepository = loginLogRepository;
        this.excelProperties = excelProperties;
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

    /**
     * 导出（同筛选参数；权限判定与 page 同径）：上限 10000 行（并受 acme.excel.max-rows 收窄），
     * 超限截断由 ExportResult.truncated 承载。
     */
    public ExportResult<SysLoginLog> export(String username, String userType,
                                            String result, Instant from, Instant to) {
        authorizationGateway.requirePermission(SystemPermissions.AUTH_LOGIN_LOG_LIST);
        long cap = Math.min(EXPORT_MAX_ROWS, Math.max(excelProperties.getMaxRows(), 1L));
        List<SysLoginLog> fetched = loginLogRepository
                .page(1, (int) (cap + 1), username, userType, result, from, to).records();
        boolean truncated = fetched.size() > cap;
        List<SysLoginLog> records = truncated ? fetched.subList(0, (int) cap) : fetched;
        return new ExportResult<>(records, truncated);
    }

    /**
     * 近 N 日登录日志按日统计（REQ-20261119-011524 确认记录②，GET /internal/v1/login-log-stats）：
     * 免权限点内部统计口径（服务身份调用，Q-D08）——聚合面仅日期+条数，无身份/敏感列，无越权面；
     * 平台级表无租户过滤（Q-D04 同型）。days 下界折算为自当前日往前 days 天的 0 点（系统时区）。
     *
     * @param days 统计天数窗口（&lt;1 视为 1，防御非法入参）
     * @return 按日升序统计点（date=YYYY-MM-DD，无日志日不出行）
     */
    public List<LoginLogStatsPoint> dailyStats(int days) {
        int window = Math.max(days, 1);
        Instant from = LocalDate.now(ZoneId.systemDefault()).minusDays(window - 1L)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        return loginLogRepository.dailyStats(from);
    }

    private static AdminLoginLogView toView(SysLoginLog entity) {
        return new AdminLoginLogView(String.valueOf(entity.getId()), entity.getUsername(), entity.getUserType(),
                entity.getIp(), entity.getUserAgent(), entity.getResult(), entity.getFailReason(),
                entity.getTraceId(), entity.getCreatedAt());
    }
}
