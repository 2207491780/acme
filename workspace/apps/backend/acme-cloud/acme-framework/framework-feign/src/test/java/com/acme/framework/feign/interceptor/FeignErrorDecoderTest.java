package com.acme.framework.feign.interceptor;

import com.acme.common.core.BizException;
import com.acme.framework.feign.support.FeignError;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下游错误解码（FR-021/AC-021、10.5.4：标准信封才取文案，否则本端固定文案；body 上限 4KB）。
 */
class FeignErrorDecoderTest {

    private final FeignErrorDecoder decoder = new FeignErrorDecoder();

    private static java.util.Map<String, java.util.Collection<String>> headers() {
        return java.util.Collections.emptyMap();
    }

    private static feign.Request request() {
        feign.RequestTemplate template = new feign.RequestTemplate();
        template.method(feign.Request.HttpMethod.GET);
        template.uri("/x");
        feign.RequestTemplate resolved = template.resolve(java.util.Collections.emptyMap());
        return resolved.request();
    }

    private static Response response(String body) {
        return Response.builder()
                .status(500)
                .reason("Server Error")
                .headers(headers())
                .body(body, StandardCharsets.UTF_8)
                .request(request())
                .build();
    }

    @Test
    void decodesStandardEnvelopeCodeAndMessage() {
        Exception decoded = decoder.decode("Demo#call", response(
                "{\"code\":\"order-10-0001\",\"message\":\"库存不足\"}"));
        assertThat(decoded).isInstanceOf(BizException.class);
        BizException biz = (BizException) decoded;
        assertThat(biz.errorCode().code()).isEqualTo("order-10-0001");
        assertThat(biz.errorCode().defaultMessage()).isEqualTo("库存不足");
    }

    @Test
    void unparseableBodyMapsToGenericFailure() {
        Exception decoded = decoder.decode("Demo#call", response("boom"));
        assertThat(decoded).isInstanceOf(BizException.class);
        assertThat(((BizException) decoded).errorCode()).isEqualTo(FeignError.CALL_FAILED);
    }

    @Test
    void nullBodyMapsToGenericFailure() {
        Response response = Response.builder()
                .status(502)
                .reason("Bad Gateway")
                .headers(headers())
                .request(request())
                .build();
        assertThat(decoder.decode("Demo#call", response))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(FeignError.CALL_FAILED));
    }

    @Test
    void nonStandardEnvelopeDoesNotLeakDownstreamMessage() {
        // 只有 message 无合法 code：不采信下游文案（10.5.4）
        Exception decoded = decoder.decode("Demo#call", response("{\"message\":\"服务暂不可用\"}"));
        BizException biz = (BizException) decoded;
        assertThat(biz.errorCode()).isEqualTo(FeignError.CALL_FAILED);
        assertThat(biz.getMessage()).isEqualTo(FeignError.CALL_FAILED.defaultMessage());
    }

    @Test
    void malformedCodeDoesNotLeakDownstreamMessage() {
        Exception decoded = decoder.decode("Demo#call", response(
                "{\"code\":\"DROP TABLE users; --\",\"message\":\"internal error at /app/Secret.java\"}"));
        assertThat(((BizException) decoded).errorCode()).isEqualTo(FeignError.CALL_FAILED);
        assertThat(((BizException) decoded).getMessage()).doesNotContain("Secret.java");
    }

    @Test
    void oversizedBodyIsTruncatedSafely() {
        String huge = "{\"code\":\"order-10-0001\",\"message\":\"" + "x".repeat(10_000) + "\"}";
        Exception decoded = decoder.decode("Demo#call", response(huge));
        assertThat(decoded).isInstanceOf(BizException.class);
    }
}
