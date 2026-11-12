package com.acme.framework.feign.internal;

import com.acme.common.core.BizException;
import com.acme.framework.feign.support.FeignError;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 传输异常映射（internal，FR-018/021：超时/失败转 BizException，不暴露内部地址）；
 * 基础设施失败判定供降级面复用（FR-020：仅熔断/超时/IO 传输失败降级）。
 */
public final class FeignErrors {

    private FeignErrors() {
    }

    public static RuntimeException map(Throwable ex) {
        if (ex instanceof BizException biz) {
            return biz;
        }
        if (isTimeout(ex)) {
            return new BizException(FeignError.TIMEOUT, "内部调用超时", ex);
        }
        return new BizException(FeignError.CALL_FAILED, "内部调用失败", ex);
    }

    /** 基础设施失败（可降级）：熔断 / 超时 / IO 传输失败（沿 cause 链判定）；业务 BizException 不在此列。 */
    public static boolean isInfrastructureFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (isCallNotPermitted(current) || isTimeout(current) || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        // 无传输根因：仅熔断/超时标记 BizException 视为基础设施失败，其余 BizException=业务透传
        current = ex;
        while (current != null) {
            if (current instanceof BizException biz) {
                return biz.errorCode() == FeignError.CIRCUIT_OPEN || biz.errorCode() == FeignError.TIMEOUT;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isCallNotPermitted(Throwable ex) {
        return ex instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException;
    }

    private static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
