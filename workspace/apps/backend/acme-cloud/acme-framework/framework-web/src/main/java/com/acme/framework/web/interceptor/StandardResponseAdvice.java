package com.acme.framework.web.interceptor;

import com.acme.common.core.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;

/**
 * 统一出参包装（FR-003、10.5.3）：非 {@link Result} 返回值包 {@code Result.ok(...)}；
 * 已是 Result 的（含异常映射结果）不再包装（BR-010 仅包装一次）。
 * 出参豁免（Modules.md 3.1.1）：void/Void、byte[]、Resource 族、StreamingResponseBody、InputStream
 * 按声明类型与运行时类型一律直通，信封不得破坏二进制/流式出参。
 */
@RestControllerAdvice
public class StandardResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(StandardResponseAdvice.class);

    private final JsonMapper jsonMapper;

    /** {@code jsonMapper} 允许缺失：String 出参降级直通（本类判空，不得 NPE）。 */
    public StandardResponseAdvice(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !isExemptType(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result) {
            return body;
        }
        if (isExemptType(returnType.getParameterType()) || isExemptBody(body)) {
            return body;
        }
        Result<Object> wrapped = Result.ok(body);
        // String 声明类型经 StringHttpMessageConverter 写出，需自行序列化信封（FR-003 仍统一包装）
        if (body instanceof String) {
            if (jsonMapper == null) {
                log.debug("JsonMapper 不可用，String 出参降级直通不包装（Modules.md 3.1.1）");
                return body;
            }
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return jsonMapper.writeValueAsString(wrapped);
        }
        return wrapped;
    }

    /** 声明类型豁免（Modules.md 3.1.1）。 */
    private static boolean isExemptType(Class<?> type) {
        return type == void.class || type == Void.class
                || type == byte[].class
                || Resource.class.isAssignableFrom(type)
                || StreamingResponseBody.class.isAssignableFrom(type)
                || InputStream.class.isAssignableFrom(type);
    }

    /** 运行时类型豁免（Modules.md 3.1.1）。 */
    private static boolean isExemptBody(Object body) {
        return body instanceof byte[] || body instanceof Resource
                || body instanceof InputStream || body instanceof StreamingResponseBody;
    }
}
