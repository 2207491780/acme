package com.acme.framework.web.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.common.core.ErrorCode;
import com.acme.common.core.FieldErrorDetail;
import com.acme.common.core.Result;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.web.support.WebError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常映射与状态码测试（FR-004/005、5.2、Q-D11、AC-005、10.5.3 新增映射）。
 */
class GlobalExceptionTranslatorTest {

    /** 模拟框架侧登记状态语义的错误码（HttpStatusAware，5.2 细分语义随错误码登记）。 */
    private static final ErrorCode DENIED = new ErrorCode() {
        @Override
        public String code() {
            return "SYS-test-0001";
        }

        @Override
        public String defaultMessage() {
            return "无权访问";
        }
    };

    private static final ErrorCode DENIED_WITH_STATUS = new ErrorCodeWith403();

    private static final class ErrorCodeWith403 implements ErrorCode, HttpStatusAware {
        @Override
        public String code() {
            return "SYS-test-0002";
        }

        @Override
        public String defaultMessage() {
            return "无权访问";
        }

        @Override
        public int httpStatus() {
            return 403;
        }
    }

    private final GlobalExceptionTranslator translator = new GlobalExceptionTranslator(provider(null));

    /** ObjectProvider 桩件（D-006 可选接入：null=无 PromptLocalizer）。 */
    private static ObjectProvider<PromptLocalizer> provider(PromptLocalizer localizer) {
        return new ObjectProvider<>() {
            @Override
            public PromptLocalizer getObject() {
                return localizer;
            }

            @Override
            public PromptLocalizer getIfAvailable() {
                return localizer;
            }

            @Override
            public java.util.Iterator<PromptLocalizer> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
    }

    /** D-006：PromptLocalizer 存在且提示为默认文案时随 Locale 输出本地化提示。 */
    @Test
    void promptLocalizerLocalizesDefaultMessage() {
        PromptLocalizer localizer = (errorCode, locale) -> "localized-" + errorCode.code();
        GlobalExceptionTranslator localized = new GlobalExceptionTranslator(provider(localizer));
        ResponseEntity<Result<Object>> response =
                localized.handleBiz(new BizException(WebError.CONCURRENCY_CONFLICT));
        assertThat(response.getBody().getMessage())
                .isEqualTo("localized-" + WebError.CONCURRENCY_CONFLICT.code());
    }

    /** D-006：业务自定义提示不被本地化替换。 */
    @Test
    void customMessageKeptWhenLocalizerPresent() {
        PromptLocalizer localizer = (errorCode, locale) -> "localized";
        GlobalExceptionTranslator localized = new GlobalExceptionTranslator(provider(localizer));
        ResponseEntity<Result<Object>> response = localized.handleBiz(
                new BizException(WebError.CONCURRENCY_CONFLICT, "业务定制提示"));
        assertThat(response.getBody().getMessage()).isEqualTo("业务定制提示");
    }

    @Test
    void bizExceptionUsesErrorRegisteredStatus() {
        ResponseEntity<Result<Object>> response = translator.handleBiz(new BizException(WebError.CONCURRENCY_CONFLICT));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(WebError.CONCURRENCY_CONFLICT.code());
    }

    @Test
    void bizExceptionWithHttpStatusAware() {
        ResponseEntity<Result<Object>> response = translator.handleBiz(new BizException(DENIED_WITH_STATUS));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void unregisteredErrorCodeDefaults500() {
        ResponseEntity<Result<Object>> response = translator.handleBiz(new BizException(DENIED));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void commonErrorNotFoundMaps404() {
        ResponseEntity<Result<Object>> response = translator.handleBiz(new BizException(CommonError.NOT_FOUND));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getCode()).isEqualTo(CommonError.NOT_FOUND.code());
    }

    @Test
    void commonErrorInternalMaps500() {
        ResponseEntity<Result<Object>> response = translator.handleBiz(new BizException(CommonError.INTERNAL_ERROR));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo(CommonError.INTERNAL_ERROR.code());
    }

    @Test
    void validationFailureCarriesFieldDetails() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "demo");
        bindingResult.addError(new FieldError("demo", "name", "不能为空"));
        ResponseEntity<Result<Object>> response = translator.handleValidation(
                new org.springframework.web.bind.MethodArgumentNotValidException(
                        new org.springframework.core.MethodParameter(
                                GlobalExceptionTranslatorTest.class.getDeclaredMethods()[0], -1),
                        bindingResult));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(WebError.VALIDATION_FAILED.code());
        assertThat(response.getBody().getData()).isEqualTo(List.of(new FieldErrorDetail("name", "不能为空")));
    }

    @Test
    void bindExceptionCarriesFieldDetails() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "demo");
        bindingResult.addError(new FieldError("demo", "seq", "必须为正"));
        ResponseEntity<Result<Object>> response = translator.handleBind(new BindException(bindingResult));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getData()).isEqualTo(List.of(new FieldErrorDetail("seq", "必须为正")));
    }

    @Test
    void badRequestMaps400() {
        ResponseEntity<Result<Object>> response = translator.handleBadRequest(
                new IllegalStateException("x"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(WebError.BAD_REQUEST.code());
    }

    @Test
    void methodNotAllowedMaps405() {
        ResponseEntity<Result<Object>> response = translator.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("PATCH"));
        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().getCode()).isEqualTo(WebError.METHOD_NOT_ALLOWED.code());
    }

    @Test
    void mediaTypeNotSupportedMaps415() {
        ResponseEntity<Result<Object>> response = translator.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("text/xml"));
        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody().getCode()).isEqualTo(WebError.MEDIA_TYPE_NOT_SUPPORTED.code());
    }

    @Test
    void mediaTypeNotAcceptableMaps406() {
        ResponseEntity<Result<Object>> response = translator.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of()));
        assertThat(response.getStatusCode().value()).isEqualTo(406);
        assertThat(response.getBody().getCode()).isEqualTo(WebError.MEDIA_TYPE_NOT_ACCEPTABLE.code());
    }

    @Test
    void optimisticLockMaps409() {
        ResponseEntity<Result<Object>> response = translator.handleOptimistic(new OptimisticLockingFailureException("x"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(WebError.CONCURRENCY_CONFLICT.code());
    }

    @Test
    void unexpectedMaps500WithoutLeakingDetails() {
        ResponseEntity<Result<Object>> response = translator.handleUnexpected(new IllegalStateException("secret-internal"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo(CommonError.INTERNAL_ERROR.code());
        assertThat(response.getBody().getMessage()).doesNotContain("secret-internal");
    }
}
