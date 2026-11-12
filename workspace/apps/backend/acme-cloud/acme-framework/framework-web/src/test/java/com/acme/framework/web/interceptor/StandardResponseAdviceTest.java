package com.acme.framework.web.interceptor;

import com.acme.common.core.Result;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一出参包装测试（FR-003、5.1、BR-010 仅包装一次、AC-004、Modules.md 3.1.1 出参豁免）。
 */
class StandardResponseAdviceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final StandardResponseAdvice advice = new StandardResponseAdvice(mapper);

    private static MethodParameter returnType() {
        return new MethodParameter(Probes.method(Object.class), -1);
    }

    private static Object write(StandardResponseAdvice advice, Object body) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        return advice.beforeBodyWrite(body, returnType(), null, null,
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(response));
    }

    // ---------- 包装（FR-003、BR-010） ----------

    @Test
    void wrapsPlainBodyWithOk() {
        Object result = write(advice, 42);
        assertThat(result).isInstanceOf(Result.class);
        assertThat(((Result<?>) result).getSuccess()).isTrue();
        assertThat(((Result<?>) result).getData()).isEqualTo(42);
    }

    @Test
    void keepsResultUnwrapped() {
        Result<String> original = Result.ok("x");
        Object result = write(advice, original);
        assertThat(result).isSameAs(original);
    }

    @Test
    void keepsFailedResultUnwrapped() {
        Result<String> original = Result.fail(com.acme.common.core.CommonError.NOT_FOUND);
        Object result = write(advice, original);
        assertThat(result).isSameAs(original);
    }

    @Test
    void wrapsNullBody() {
        Object result = write(advice, null);
        assertThat(result).isInstanceOf(Result.class);
    }

    // ---------- String 路径（FR-003） ----------

    @Test
    void stringBodySerializedAsEnvelope() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object result = advice.beforeBodyWrite("hello", returnType(), null, null,
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(response));
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("\"data\":\"hello\"");
        assertThat(response.getContentType()).contains("application/json");
    }

    @Test
    void stringBodyWithoutJsonMapperPassesThrough() {
        StandardResponseAdvice noMapper = new StandardResponseAdvice(null);
        Object result = write(noMapper, "hello");
        assertThat(result).isEqualTo("hello");
    }

    // ---------- 出参豁免（Modules.md 3.1.1） ----------

    @Test
    void voidReturnTypesAreNotSupported() {
        for (Class<?> type : new Class<?>[]{void.class, Void.class}) {
            Method method = Probes.method(type);
            assertThat(advice.supports(new MethodParameter(method, -1), null))
                    .as("声明类型 %s 不应包装", type)
                    .isFalse();
        }
    }

    @Test
    void binaryReturnTypesAreNotSupported() {
        for (Class<?> type : new Class<?>[]{byte[].class, Resource.class, InputStream.class,
                StreamingResponseBody.class, ByteArrayResource.class}) {
            Method method = Probes.method(type);
            assertThat(new MethodParameter(method, -1).getParameterType())
                    .as("探针返回类型应为 %s", type)
                    .isEqualTo(type);
            assertThat(advice.supports(new MethodParameter(method, -1), null))
                    .as("声明类型 %s 不应包装", type)
                    .isFalse();
        }
    }

    @Test
    void binaryBodiesPassThroughUnwrapped() {
        for (Object body : new Object[]{new byte[]{1, 2}, new ByteArrayResource(new byte[]{1}),
                new ByteArrayInputStream(new byte[]{1})}) {
            Object result = write(advice, body);
            assertThat(result).as("运行时类型 %s 应直通", body.getClass()).isSameAs(body);
        }
    }

    @Test
    void streamingBodyPassesThroughUnwrapped() {
        StreamingResponseBody body = outputStream -> { };
        Object result = write(advice, body);
        assertThat(result).isSameAs(body);
    }

    /** 非豁免声明类型（Object）不触发 supports 短路时仍按运行时类型豁免。 */
    @Test
    void declaredObjectTypeWithBinaryBodyStaysUnwrapped() {
        Method method = Probes.method(Object.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object result = advice.beforeBodyWrite(new byte[]{9}, new MethodParameter(method, -1), null, null,
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(response));
        assertThat(result).isInstanceOf(byte[].class);
    }

    /** 固定返回类型的探针集合（仅用于构造 MethodParameter(-1) 返回类型）。 */
    static class Probes {

        void voidProbe() {
        }

        Void boxedVoidProbe() {
            return null;
        }

        byte[] bytesProbe() {
            return null;
        }

        Resource resourceProbe() {
            return null;
        }

        InputStream streamProbe() {
            return null;
        }

        StreamingResponseBody streamingProbe() {
            return null;
        }

        ByteArrayResource resourceSubclassProbe() {
            return null;
        }

        Object objectProbe() {
            return null;
        }

        static Method method(Class<?> type) {
            if (type == void.class) {
                return find("voidProbe");
            }
            if (type == Void.class) {
                return find("boxedVoidProbe");
            }
            if (type == byte[].class) {
                return find("bytesProbe");
            }
            if (type == Resource.class) {
                return find("resourceProbe");
            }
            if (type == InputStream.class) {
                return find("streamProbe");
            }
            if (type == StreamingResponseBody.class) {
                return find("streamingProbe");
            }
            if (type == ByteArrayResource.class) {
                return find("resourceSubclassProbe");
            }
            return find("objectProbe");
        }

        private static Method find(String name) {
            for (Method method : Probes.class.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    return method;
                }
            }
            throw new IllegalStateException("探针方法缺失：" + name);
        }
    }
}
