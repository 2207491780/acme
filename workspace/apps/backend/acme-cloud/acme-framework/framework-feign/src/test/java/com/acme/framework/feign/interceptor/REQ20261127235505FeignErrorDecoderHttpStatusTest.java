package com.acme.framework.feign.interceptor;

import com.acme.common.core.BizException;
import com.acme.framework.feign.support.FeignError;
import com.acme.framework.feign.support.RemoteCallException;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261127-235505 B2a（确认⑥①方案 A）Feign 错误解码 HTTP 状态码保真单测：
 * {@link FeignErrorDecoder} 产物改投 {@link RemoteCallException}（extends BizException，
 * 携带 int httpStatus）——解码产物必须 isInstanceOf(BizException) 且
 * instanceof RemoteCallException，httpStatus() 与响应状态一致；errorCode 语义不变
 * （标准信封采信 code/message；非信封得 {@link FeignError#CALL_FAILED} 与其 defaultMessage，
 * 不泄露下游 message）。
 *
 * <p>request()/body 构造照抄既有 {@link FeignErrorDecoderTest}；其 helper 固定 500，
 * 本类自建可变 status 的 helper。四腿：404 信封 / 404 非信封 / 500 信封 / 502 无 body。</p>
 *
 * <p>AC→用例映射：AC-3 状态码载体（404/非 404 状态保真，workflow 客户端判定面见
 * {@code REQ20261127235505SchedulerRegistrationClientTest}）。</p>
 */
class REQ20261127235505FeignErrorDecoderHttpStatusTest {

    private final FeignErrorDecoder decoder = new FeignErrorDecoder();

    private static feign.Request request() {
        feign.RequestTemplate template = new feign.RequestTemplate();
        template.method(feign.Request.HttpMethod.GET);
        template.uri("/x");
        feign.RequestTemplate resolved = template.resolve(Collections.emptyMap());
        return resolved.request();
    }

    /** 可变 status 的响应构造（body 可空：502 无 body 腿）。 */
    private static Response response(int status, String body) {
        Response.Builder builder = Response.builder()
                .status(status)
                .reason("Http " + status)
                .headers(Collections.emptyMap())
                .request(request());
        if (body != null) {
            builder.body(body, StandardCharsets.UTF_8);
        }
        return builder.build();
    }

    /** 状态码载体断言（AC-3）：BizException 且 RemoteCallException，httpStatus 保真。 */
    private static void assertHttpStatusCarried(Exception decoded, int expectedStatus) {
        assertThat(decoded)
                .as("解码产物应是 BizException").isInstanceOf(BizException.class)
                .as("解码产物应是 RemoteCallException（方案 A 状态载体）")
                .isInstanceOf(RemoteCallException.class);
        assertThat(((RemoteCallException) decoded).httpStatus())
                .as("异常应携带响应 HTTP 状态码").isEqualTo(expectedStatus);
    }

    // ---------- 腿 1：404 + 标准信封（code 采信 + 状态保真） ----------

    @Test
    void should_carry_http_status_when_decoding_standard_envelope_with_404() {
        Exception decoded = decoder.decode("Demo#call", response(404,
                "{\"code\":\"order-10-0002\",\"message\":\"下游资源不存在\"}"));
        assertHttpStatusCarried(decoded, 404);
        BizException biz = (BizException) decoded;
        assertThat(biz.errorCode().code()).as("标准信封 code 应采信").isEqualTo("order-10-0002");
        assertThat(biz.errorCode().defaultMessage()).as("标准信封 message 应采信")
                .isEqualTo("下游资源不存在");
    }

    // ---------- 腿 2：404 + 非标准信封（generic 错误 + 状态保真 + 不泄露文案） ----------

    @Test
    void should_carry_http_status_and_generic_error_when_404_body_is_not_standard_envelope() {
        Exception decoded = decoder.decode("Demo#call", response(404,
                "{\"message\":\"no route to legacy scheduler\"}"));
        assertHttpStatusCarried(decoded, 404);
        BizException biz = (BizException) decoded;
        assertThat(biz.errorCode()).as("非标准信封应得 FeignError.CALL_FAILED")
                .isEqualTo(FeignError.CALL_FAILED);
        assertThat(biz.getMessage()).as("文案应为本端固定 defaultMessage")
                .isEqualTo(FeignError.CALL_FAILED.defaultMessage());
        assertThat(biz.getMessage()).as("不得泄露下游 message").doesNotContain("no route");
    }

    // ---------- 腿 3：500 + 标准信封（状态保真不因信封采信而丢失） ----------

    @Test
    void should_carry_http_status_when_decoding_standard_envelope_with_500() {
        Exception decoded = decoder.decode("Demo#call", response(500,
                "{\"code\":\"order-10-0001\",\"message\":\"库存不足\"}"));
        assertHttpStatusCarried(decoded, 500);
        assertThat(((BizException) decoded).errorCode().code())
                .as("标准信封 code 应采信").isEqualTo("order-10-0001");
    }

    // ---------- 腿 4：502 无 body（generic 错误 + 状态保真） ----------

    @Test
    void should_carry_http_status_when_decoding_502_without_body() {
        Exception decoded = decoder.decode("Demo#call", response(502, null));
        assertHttpStatusCarried(decoded, 502);
        assertThat(((BizException) decoded).errorCode())
                .as("无 body 应得 FeignError.CALL_FAILED").isEqualTo(FeignError.CALL_FAILED);
    }
}
