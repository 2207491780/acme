package com.acme.framework.gateway.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.framework.gateway.internal.ErrorBodies;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关自身错误统一封装（FR-025、8.8、Q-D11）：仅网关自身错误，业务响应不得二次包装（BR-010）；
 * AUTH/TOKEN→401、越端→403、限流→429、路由失败→502/504（超时 504）、BAD_REQUEST→400；
 * 封装过程异常→最小化标准错误（FR-025 异常分支、10.5.4）。
 */
public final class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        try {
            Mapped mapped = map(ex);
            return ErrorBodies.write(response, mapped.status(), mapped.errorCode());
        } catch (RuntimeException mapEx) {
            log.warn("网关错误封装失败，最小化标准错误：{}", mapEx.getClass().getName());
            return ErrorBodies.write(response, 500, CommonError.INTERNAL_ERROR);
        }
    }

    private static Mapped map(Throwable ex) {
        if (ex instanceof GatewayRejectedException rejected) {
            return new Mapped(rejected.httpStatus(), rejected.errorCode());
        }
        if (ex instanceof BizException biz) {
            ErrorCode errorCode = biz.errorCode();
            return new Mapped(statusOf(errorCode), errorCode);
        }
        if (ex instanceof ResponseStatusException statusEx) {
            int status = statusEx.getStatusCode().value();
            if (status == 404) {
                return new Mapped(404, CommonError.NOT_FOUND);
            }
            if (status >= 400 && status < 500) {
                return new Mapped(400, GatewayError.BAD_REQUEST);
            }
            return new Mapped(502, GatewayError.ROUTE_FAILED);
        }
        if (isTimeout(ex)) {
            return new Mapped(504, GatewayError.ROUTE_FAILED);
        }
        if (isRoutingFailure(ex)) {
            return new Mapped(502, GatewayError.ROUTE_FAILED);
        }
        log.warn("网关未分类错误（不对外泄露细节，10.5.4）：{}", ex.getClass().getName(), ex);
        return new Mapped(500, CommonError.INTERNAL_ERROR);
    }

    private static int statusOf(ErrorCode errorCode) {
        if (errorCode instanceof HttpStatusAware aware) {
            return aware.httpStatus();
        }
        if (errorCode == CommonError.NOT_FOUND) {
            return 404;
        }
        // 未映射错误码默认 500：语义未知不得按 400 误报（10.5.3）
        return 500;
    }

    private static boolean isTimeout(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRoutingFailure(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String name = current.getClass().getName();
            if (name.contains("ConnectException") || name.contains("WebClientRequestException")
                    || name.contains("PrematureCloseException")) {
                return true;
            }
        }
        return false;
    }

    /** 优先于 Boot 缺省错误处理器（8.8）。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 500;
    }

    private record Mapped(int status, ErrorCode errorCode) {
    }
}
