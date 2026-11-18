package com.acme.platform.auth.application;

import com.acme.framework.excel.config.ExcelProperties;
import com.acme.platform.auth.domain.SysLoginLog;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.repository.LoginLogRepository;
import com.acme.platform.system.api.SystemPermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录日志导出单元测试（REQ-20261117-061017 确认④，AC-6 用例面；纯 JUnit + Mockito）：
 * 上限截断（受 acme.excel.max-rows 收窄）与权限判定同径（auth:loginlog:list）。
 */
class LoginLogExportTest {

    private AuthorizationGateway authorizationGateway;
    private LoginLogRepository loginLogRepository;
    private LoginLogQueryUseCase useCase;

    @BeforeEach
    void setUp() {
        authorizationGateway = mock(AuthorizationGateway.class);
        loginLogRepository = mock(LoginLogRepository.class);
        useCase = new LoginLogQueryUseCase(authorizationGateway, loginLogRepository, new ExcelProperties());
    }

    private static SysLoginLog row(String username) {
        return new SysLoginLog(username, "B", "127.0.0.1", "ua", SysLoginLog.RESULT_SUCCESS,
                null, "trace-1");
    }

    /** 未超上限：原样返回，truncated=false，权限判定先执行（确认④同 list 权限）。 */
    @Test
    void should_exportRowsWithoutTruncation_when_withinCap() {
        List<SysLoginLog> rows = List.of(row("admin"), row("demo_operator"));
        when(loginLogRepository.page(eq(1), eq(10001), any(), any(), any(), any(), any()))
                .thenReturn(new com.acme.common.contract.PageResult<>(rows, 2L));

        ExportResult<SysLoginLog> result = useCase.export(null, null, null, null, null);

        assertThat(result.truncated()).isFalse();
        assertThat(result.records()).hasSize(2);
        verify(authorizationGateway).requirePermission(SystemPermissions.AUTH_LOGIN_LOG_LIST);
    }

    /** 超上限（注入小上限）：截断到上限 + truncated=true（确认④）。 */
    @Test
    void should_truncateExport_when_rowsExceedInjectedCap() {
        useCase = new LoginLogQueryUseCase(authorizationGateway, loginLogRepository,
                excelPropertiesWithMaxRows(2));
        List<SysLoginLog> rows = List.of(row("a"), row("b"), row("c"));
        when(loginLogRepository.page(eq(1), eq(3), any(), any(), any(), any(), any()))
                .thenReturn(new com.acme.common.contract.PageResult<>(rows, 3L));

        ExportResult<SysLoginLog> result = useCase.export(null, null, null, null, null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.records()).hasSize(2);
    }

    private static ExcelProperties excelPropertiesWithMaxRows(long maxRows) {
        ExcelProperties properties = new ExcelProperties();
        properties.setMaxRows(maxRows);
        return properties;
    }
}
