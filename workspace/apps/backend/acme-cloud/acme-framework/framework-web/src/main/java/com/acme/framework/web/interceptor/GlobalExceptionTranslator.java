package com.acme.framework.web.interceptor;

import com.acme.common.context.LocaleContext;
import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.common.core.ErrorCode;
import com.acme.common.core.FieldErrorDetail;
import com.acme.common.core.Result;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.web.internal.ErrorLoggers;
import com.acme.framework.web.support.WebError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全局异常与校验错误映射（FR-004/005、10.5；Q-D11 语义化状态码 + Result 信封）；
 * 不暴露堆栈/SQL/内部地址（10.5.4、BR-020）；映射过程自身异常兜底最小标准失败响应（FR-004 异常分支）。
 * 面向调用方提示经可选 {@link PromptLocalizer} 随 Locale 输出（p5-optional-modules D-006，
 * 缺省不启用时输出 Error 枚举默认文案，10.5.1）。
 */
@RestControllerAdvice
public class GlobalExceptionTranslator {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionTranslator.class);

    private final ObjectProvider<PromptLocalizer> promptLocalizers;

    public GlobalExceptionTranslator(ObjectProvider<PromptLocalizer> promptLocalizers) {
        this.promptLocalizers = promptLocalizers;
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
        try {
            return ResponseEntity.status(WebError.BAD_REQUEST.httpStatus())
                    .body(Result.fail(WebError.BAD_REQUEST));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 请求方法不支持（10.5.3）：405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMethodNotAllowed(Exception ex) {
        try {
            return ResponseEntity.status(WebError.METHOD_NOT_ALLOWED.httpStatus())
                    .body(Result.fail(WebError.METHOD_NOT_ALLOWED));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 请求内容类型不支持（10.5.3）：415。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMediaTypeNotSupported(Exception ex) {
        try {
            return ResponseEntity.status(WebError.MEDIA_TYPE_NOT_SUPPORTED.httpStatus())
                    .body(Result.fail(WebError.MEDIA_TYPE_NOT_SUPPORTED));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 响应内容类型不可接受（10.5.3）：406。 */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Result<Object>> handleMediaTypeNotAcceptable(Exception ex) {
        try {
            return ResponseEntity.status(WebError.MEDIA_TYPE_NOT_ACCEPTABLE.httpStatus())
                    .body(Result.fail(WebError.MEDIA_TYPE_NOT_ACCEPTABLE));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 目标不存在（10.5.3）：复用 CommonError.NOT_FOUND，404。 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Object>> handleNotFound(Exception ex) {
        try {
            return ResponseEntity.status(404).body(Result.fail(CommonError.NOT_FOUND));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 乐观锁冲突（FR-010、5.2）：409 可判定。 */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Result<Object>> handleOptimistic(OptimisticLockingFailureException ex) {
        try {
            return ResponseEntity.status(WebError.CONCURRENCY_CONFLICT.httpStatus())
                    .body(Result.fail(WebError.CONCURRENCY_CONFLICT));
        } catch (RuntimeException mapEx) {
            return fallback(mapEx);
        }
    }

    /** 未知异常（10.5.4）：INTERNAL_ERROR + 带 traceId 的 ERROR 日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnexpected(Exception ex) {
        try {
            ErrorLoggers.logUnexpected(ex);
            return ResponseEntity.status(500).body(Result.fail(CommonError.INTERNAL_ERROR));
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
}
