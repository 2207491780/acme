package com.acme.platform.auth.application;

import com.acme.framework.trace.support.Traces;
import com.acme.common.context.TraceContext;
import com.acme.platform.auth.domain.SysLoginLog;
import com.acme.platform.auth.infrastructure.repository.LoginLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 登录日志记录器（REQ-20261114-120105 AC-1）：登录成功/失败均异步落库，写失败仅 WARN
 * 不影响登录主路径。traceId 在调用线程（登录请求链路）捕获后随条目落库——@Async 切换线程
 * 不再持有 MDC/TraceContext。Spring 装配下经代理异步执行（applicationTaskExecutor）；
 * 非代理直构（单测）时同步执行，便于确定性断言。
 */
@Component
public class LoginLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoginLogRecorder.class);

    private final LoginLogRepository repository;

    public LoginLogRecorder(LoginLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 异步落库（登录用例成功/失败分支调用；任何失败仅 WARN，绝不外抛阻断登录）。
     *
     * @param entry 日志条目（经 {@link Entry#capture} 在登录线程构造，traceId 已随行）
     */
    @Async
    public void record(Entry entry) {
        try {
            repository.save(new SysLoginLog(entry.username(), entry.userType(), entry.ip(), entry.userAgent(),
                    entry.result(), entry.failReason(), entry.traceId()));
        } catch (RuntimeException ex) {
            log.warn("登录日志落库失败（不影响登录主路径）：username={} result={}",
                    entry.username(), entry.result(), ex);
        }
    }

    /**
     * 登录日志条目（REQ-20261114-120105 AC-1 字段口径：username/userType/ip/ua/result/failReason/traceId）。
     *
     * @param username   用户名（C 端为手机号）
     * @param userType   端别（B/C，与会话 channel 一致）
     * @param ip         登录来源 IP
     * @param userAgent  登录 UA
     * @param result     SUCCESS/FAILURE
     * @param failReason 失败原因（错误码；成功为 null）
     * @param traceId    登录请求链路 traceId（调用线程捕获）
     */
    public record Entry(String username, String userType, String ip, String userAgent,
                        String result, String failReason, String traceId) {

        /** 在登录请求线程构造条目（traceId 随行；链路上下文缺失置 null）。 */
        public static Entry capture(String username, String userType, String ip, String userAgent,
                                    boolean success, String failReason) {
            TraceContext trace = Traces.current();
            return new Entry(username, userType, ip, userAgent,
                    success ? SysLoginLog.RESULT_SUCCESS : SysLoginLog.RESULT_FAILURE,
                    failReason, trace == null ? null : trace.traceId());
        }
    }
}
