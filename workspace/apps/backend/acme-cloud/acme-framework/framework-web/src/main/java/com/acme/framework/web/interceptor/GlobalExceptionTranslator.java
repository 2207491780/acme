package com.acme.framework.web.interceptor;

import com.acme.common.contract.InvalidRequestException;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.common.core.ErrorCode;
import com.acme.common.core.FieldErrorDetail;
import com.acme.common.core.Result;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.web.event.OpExceptionCapturedEvent;
import com.acme.framework.web.internal.ErrorLoggers;
import com.acme.framework.web.support.WebError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全局异常与校验错误映射（FR-004/005、10.5；Q-D11 语义化状态码 + Result 信封）；
 * 不暴露堆栈/SQL/内部地址（10.5.4、BR-020）；映射过程自身异常兜底最小标准失败响应（FR-004 异常分支）。
 * 面向调用方提示经可选 {@link PromptLocalizer} 随 Locale 输出（p5-optional-modules D-006，
 * 缺省不启用时输出 Error 枚举默认文案，10.5.1）。
 * REQ-20261117-061017：系统异常 500 路径发布 {@link OpExceptionCapturedEvent}（确认①：仅兜底
 * Exception 与 DataAccessException 还原失败的 500；BizException/4xx 不发）——发布失败/无监听者静默，
 * 纯框架层零侵入；开关 {@code acme.exception-log.enabled}（默认 true）。
 */
@RestControllerAdvice
public class GlobalExceptionTranslator {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionTranslator.class);

    /** 堆栈摘要截断上限（REQ-20261117-061017 确认①：发布侧 8KB）。 */
    static final int STACK_SUMMARY_MAX_LENGTH = 8 * 1024;

    private static final String DEFAULT_SERVICE_NAME = "application";

    private final ObjectProvider<PromptLocalizer> promptLocalizers;

    private final ObjectProvider<ApplicationEventPublisher> eventPublishers;

    private final String serviceName;

    private final boolean exceptionLogEnabled;

    public GlobalExceptionTranslator(ObjectProvider<PromptLocalizer> promptLocalizers) {
        this(promptLocalizers, null, DEFAULT_SERVICE_NAME, true);
    }

    public GlobalExceptionTranslator(ObjectProvider<PromptLocalizer> promptLocalizers,
                                     ObjectProvider<ApplicationEventPublisher> eventPublishers,
                                     String serviceName, boolean exceptionLogEnabled) {
        this.promptLocalizers = promptLocalizers;
        this.eventPublishers = eventPublishers;
        this.serviceName = serviceName == null || serviceName.isBlank()
                ? DEFAULT_SERVICE_NAME : serviceName;
        this.exceptionLogEnabled = exceptionLogEnabled;
    }

    /** 业务异常（10.5.1、5.2）：状态码随错误码登记语义（HttpStatusAware），未映射默认 500。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBiz(BizException ex) {
        try {
            ErrorCode errorCode = ex.errorCode();
            return ResponseEntity.status(statusOf(errorCode))
                    .body(Result.fail(errorCode, prompt(errorCode, ex.getMessage())));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 校验错误映射（FR-005、10.5.2）：字段级明细放 data（Q-D05）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(new FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        return validationFailure(details);
    }

    /** 约束违规（FR-005、10.5.2）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraint(ConstraintViolationException ex) {
        List<FieldErrorDetail> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            details.add(new FieldErrorDetail(violation.getPropertyPath().toString(), violation.getMessage()));
        }
        return validationFailure(details);
    }

    /** 绑定异常（FR-005、10.5.2）。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Object>> handleBind(BindException ex) {
        List<FieldErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(new FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        return validationFailure(details);
    }

    /** 请求不合法（10.5.3）：消息不可读 / 类型不匹配 / 缺参数 → 400。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Object>> handleBadRequest(Exception ex) {
        return respond(WebError.BAD_REQUEST);
    }

    /**
     * 处理器方法级参数校验失败 → 400（FR-005、10.5.2 口径；产品修复 REQ-20261118-002130 测试债
     * 清理实证）：Spring Framework 6.1+ 对 {@code @RequestParam} 等约束注解走方法级校验，
     * 抛 {@link HandlerMethodValidationException}（Framework 7 起 HandlerMethod 绑定面不再落
     * ConstraintViolationException）；无专属映射时被兜底 Exception 处理器卷成 500，违反
     * 「校验失败=400 信封」契约（批量下线 username 空白 400 用例实证）。
     */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<Result<Object>> handleMethodValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex) {
        return respond(WebError.BAD_REQUEST);
    }

    /** 协议基元参数越界（review 修复）：分页等 contract 类型入参不合法 → 400，不再落 500。 */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Result<Object>> handleInvalidRequest(InvalidRequestException ex) {
        return respond(WebError.BAD_REQUEST);
    }

    /** 请求方法不支持（10.5.3）：405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMethodNotAllowed(Exception ex) {
        return respond(WebError.METHOD_NOT_ALLOWED);
    }

    /** 请求内容类型不支持（10.5.3）：415。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMediaTypeNotSupported(Exception ex) {
        return respond(WebError.MEDIA_TYPE_NOT_SUPPORTED);
    }

    /** 响应内容类型不可接受（10.5.3）：406。 */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Result<Object>> handleMediaTypeNotAcceptable(Exception ex) {
        return respond(WebError.MEDIA_TYPE_NOT_ACCEPTABLE);
    }

    /** 目标不存在（10.5.3）：复用 CommonError.NOT_FOUND，404。 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Object>> handleNotFound(Exception ex) {
        return respond(CommonError.NOT_FOUND);
    }

    /** 乐观锁冲突（FR-010、5.2）：409 可判定。 */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Result<Object>> handleOptimistic(OptimisticLockingFailureException ex) {
        return respond(WebError.CONCURRENCY_CONFLICT);
    }

    /**
     * 数据访问包装异常：MyBatis 监听器（如 TenantWriteGuard 缺租户写入拒绝）内抛出的 BizException
     * 会被包装为 MyBatisSystemException（UncategorizedDataAccessException 子类，root cause 保留错误码），
     * 按精确 BizException 匹配接不到（FR-016）；沿因果链还原首个 BizException 走业务语义，
     * 非业务根因按未知异常处理（10.5.4 不泄露细节）。乐观锁已有更精确的专属映射，不受影响。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Object>> handleDataAccess(DataAccessException ex) {
        for (Throwable cause = ex.getCause(); cause != null && cause != ex; cause = cause.getCause()) {
            if (cause instanceof BizException biz) {
                return handleBiz(biz);
            }
        }
        return handleUnexpected(ex);
    }

    /** 未知异常（10.5.4）：INTERNAL_ERROR + 带 traceId 的 ERROR 日志；500 路径发布异常捕获事件（确认①）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnexpected(Exception ex) {
        try {
            ErrorLoggers.logUnexpected(ex);
            publishOpException(ex);
            return ResponseEntity.status(500).body(Result.fail(CommonError.INTERNAL_ERROR));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 固定错误码失败响应（S14 收敛各专属映射共用出口）：状态码随错误码登记语义，含映射兜底。 */
    private static ResponseEntity<Result<Object>> respond(ErrorCode errorCode) {
        try {
            return ResponseEntity.status(statusOf(errorCode)).body(Result.fail(errorCode));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 映射过程自身异常兜底（FR-004 异常分支）：最小标准失败响应，不泄露实现细节。 */
    private static ResponseEntity<Result<Object>> fallback(RuntimeException mapEx) {
        log.warn("异常映射自身失败，兜底标准失败响应：{}", mapEx.getClass().getName());
        return ResponseEntity.status(500).body(Result.fail(CommonError.INTERNAL_ERROR));
    }

    /** 校验类失败统一出口（10.5.2）：VALIDATION_FAILED + 字段级明细，含映射兜底。 */
    private ResponseEntity<Result<Object>> validationFailure(List<FieldErrorDetail> details) {
        try {
            return ResponseEntity.status(WebError.VALIDATION_FAILED.httpStatus())
                    .body(Result.fail(WebError.VALIDATION_FAILED,
                            prompt(WebError.VALIDATION_FAILED, WebError.VALIDATION_FAILED.defaultMessage()), details));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /**
     * 面向调用方提示随 Locale 输出（D-006）：无 PromptLocalizer 或本地化缺失时回退原提示
     * （Error 枚举默认文案，10.5.1）；业务自定义提示不替换。
     */
    private String prompt(ErrorCode errorCode, String currentMessage) {
        PromptLocalizer localizer = promptLocalizers.getIfAvailable();
        if (localizer == null || errorCode == null) {
            return currentMessage;
        }
        try {
            if (currentMessage != null && !currentMessage.equals(errorCode.defaultMessage())) {
                return currentMessage;
            }
            LocaleContext localeContext = LocaleContext.get();
            Locale locale = localeContext == null ? null : localeContext.locale();
            String localized = localizer.localize(errorCode, locale);
            return localized == null ? currentMessage : localized;
        } catch (RuntimeException ex) {
            return currentMessage;
        }
    }

    /**
     * 细分语义随错误码登记（5.2）：HttpStatusAware 优先，CommonError 按枚举实例判定，
     * 未映射错误码默认 500（不得默认 400，避免把服务端错误误判为客户端错误）。
     */
    private static int statusOf(ErrorCode errorCode) {
        if (errorCode instanceof HttpStatusAware aware) {
            return aware.httpStatus();
        }
        if (errorCode == CommonError.NOT_FOUND) {
            return 404;
        }
        if (errorCode == CommonError.INTERNAL_ERROR) {
            return 500;
        }
        return 500;
    }

    /**
     * 异常捕获事件发布（REQ-20261117-061017 确认①）：开关关闭/无事件出口/发布失败均静默，
     * 绝不影响异常响应主路径；上下文读取 try-closed（无上下文时留空）。
     */
    private void publishOpException(Throwable ex) {
        try {
            if (!exceptionLogEnabled || eventPublishers == null) {
                return;
            }
            ApplicationEventPublisher publisher = eventPublishers.getIfAvailable();
            if (publisher == null) {
                return;
            }
            publisher.publishEvent(buildEvent(ex));
        } catch (RuntimeException publishEx) {
            log.debug("异常捕获事件发布失败（不影响响应）: {}", publishEx.getMessage());
        }
    }

    /** 事件组装（发布侧截断堆栈 8KB；请求/上下文信息尽力捕获，缺失置 null）。 */
    private OpExceptionCapturedEvent buildEvent(Throwable ex) {
        TraceContext trace = TraceContext.get();
        TenantContext tenant = TenantContext.get();
        UserContext user = UserContext.get();
        return new OpExceptionCapturedEvent(
                serviceName,
                trace == null ? null : trace.traceId(),
                trace == null ? null : trace.spanId(),
                tenant == null ? null : tenant.tenantId(),
                user == null ? null : user.userId(),
                currentRequest(HttpServletRequest::getMethod),
                currentRequest(request -> request.getRequestURI()),
                500,
                ex.getClass().getName(),
                ex.getMessage(),
                stackSummary(ex),
                Instant.now());
    }

    /** 请求信息读取（非 Web 语境/读取失败返回 null，不影响发布；确认① try-closed）。 */
    private static String currentRequest(java.util.function.Function<HttpServletRequest, String> extractor) {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                HttpServletRequest request = servletAttributes.getRequest();
                return extractor.apply(request);
            }
        } catch (RuntimeException ignored) {
            // 非 Web 语境（异步/消息线程）：留空
        }
        return null;
    }

    /** 堆栈摘要（发布侧截断 8KB，确认①）；取栈失败置 null。 */
    private static String stackSummary(Throwable ex) {
        try {
            StringWriter out = new StringWriter(1024);
            ex.printStackTrace(new PrintWriter(out));
            String stack = out.toString();
            return stack.length() <= STACK_SUMMARY_MAX_LENGTH
                    ? stack : stack.substring(0, STACK_SUMMARY_MAX_LENGTH);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
