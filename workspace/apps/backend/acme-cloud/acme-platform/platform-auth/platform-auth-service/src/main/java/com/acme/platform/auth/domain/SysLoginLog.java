package com.acme.platform.auth.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 登录日志（REQ-20261114-120105：platform-auth V6 sys_login_log）：登录成功/失败审计记录，
 * 业务语义仅插入不改（无更新端点）；B/C 双端共用（user_type=channel）。
 * 平台级表：不含 tenant_id（登录链路无租户上下文，Q-D04 同型）；
 * deleted/version 通用列保留（保留期清理任务按「软删超期物理清理」范式执行）。
 */
@Table("sys_login_log")
public class SysLoginLog extends AuditBase {

    /** 登录结果（result 列取值）。 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILURE = "FAILURE";

    @Column("username")
    private String username;

    @Column("user_type")
    private String userType;

    @Column("ip")
    private String ip;

    @Column("user_agent")
    private String userAgent;

    @Column("result")
    private String result;

    /** 失败原因（错误码；成功为 null）。 */
    @Column("fail_reason")
    private String failReason;

    @Column("trace_id")
    private String traceId;

    protected SysLoginLog() {
    }

    public SysLoginLog(String username, String userType, String ip, String userAgent,
                       String result, String failReason, String traceId) {
        this.username = username;
        this.userType = userType;
        this.ip = ip;
        this.userAgent = userAgent;
        this.result = result;
        this.failReason = failReason;
        this.traceId = traceId;
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
}
