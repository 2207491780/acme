package com.acme.platform.auth.application;

import com.acme.framework.excel.support.ExcelField;
import com.acme.framework.excel.support.ExcelSheet;
import com.acme.platform.auth.domain.SysLoginLog;

/**
 * 登录日志导出行（REQ-20261117-061017 确认④：导出列 = 登录日志列表页列集 AdminLoginLogView 对齐；
 * 映射声明归业务侧 BR-010，DemoRow 先例同型；时间以 ISO-8601 文本承载）。
 */
@ExcelSheet("login-logs")
public class LoginLogExportRow {

    @ExcelField(value = "ID", order = 1)
    private String id;

    @ExcelField(value = "用户名", order = 2)
    private String username;

    @ExcelField(value = "端别", order = 3)
    private String userType;

    @ExcelField(value = "IP", order = 4)
    private String ip;

    @ExcelField(value = "UserAgent", order = 5)
    private String userAgent;

    @ExcelField(value = "结果", order = 6)
    private String result;

    @ExcelField(value = "失败原因", order = 7)
    private String failReason;

    @ExcelField(value = "链路ID", order = 8)
    private String traceId;

    @ExcelField(value = "记录时间", order = 9)
    private String createdAt;

    public LoginLogExportRow() {
    }

    /** 实体 → 导出行（createdAt ISO-8601 文本）。 */
    public static LoginLogExportRow from(SysLoginLog entity) {
        LoginLogExportRow row = new LoginLogExportRow();
        row.id = entity.getId() == null ? null : String.valueOf(entity.getId());
        row.username = entity.getUsername();
        row.userType = entity.getUserType();
        row.ip = entity.getIp();
        row.userAgent = entity.getUserAgent();
        row.result = entity.getResult();
        row.failReason = entity.getFailReason();
        row.traceId = entity.getTraceId();
        row.createdAt = entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString();
        return row;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getUserType() {
        return userType;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getResult() {
        return result;
    }

    public String getFailReason() {
        return failReason;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
