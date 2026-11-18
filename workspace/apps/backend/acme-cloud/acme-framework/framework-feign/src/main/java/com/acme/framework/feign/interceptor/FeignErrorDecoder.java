package com.acme.framework.feign.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.feign.internal.RemoteErrorCode;
import com.acme.framework.feign.support.FeignError;
import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * 下游错误解码（FR-021/AC-021）：错误响应 → BizException；
 * 仅当错误体可解析为标准信封（有 code 字段且符合错误码格式）时取其错误码与固定文案，
 * 否则用本端固定文案（10.5.4 不泄露下游 message/堆栈/SQL/内部地址）；body 读取上限 4KB。
 */
public class FeignErrorDecoder implements ErrorDecoder {

    /** 错误体读取上限（10.5.4 防大响应拖垮解码面）。 */
    private static final int BODY_LIMIT_BYTES = 4096;

    @Override
    public Exception decode(String methodKey, Response response) {
        String body = readBody(response);
        EnvelopeFields fields = parseEnvelope(body);
        if (fields != null) {
            return new BizException(new RemoteErrorCode(fields.code(), fields.message()));
        }
        // 非标准信封：本端固定文案，不透传下游 message
        return new BizException(FeignError.CALL_FAILED, FeignError.CALL_FAILED.defaultMessage());
    }

    /** 标准信封字段（code 必须符合错误码格式，否则整体不采信）。 */
    record EnvelopeFields(String code, String message) {
    }

    /** 解析标准错误信封；不符合错误码格式或缺 code 返回 null。 */
    static EnvelopeFields parseEnvelope(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String code = extractString(body, "code");
        if (code == null || !isWellFormedCode(code)) {
            return null;
        }
        String message = extractString(body, "message");
        return new EnvelopeFields(code, message == null ? "下游调用失败" : message);
    }

    /** 错误码格式（DP-008 三段式）：serviceId-模块段-序号，模块段 2 位、序号 4 位。 */
    private static boolean isWellFormedCode(String code) {
        int lastHyphen = code.lastIndexOf('-');
        int secondLast = lastHyphen < 0 ? -1 : code.lastIndexOf('-', lastHyphen - 1);
        if (secondLast <= 0 || lastHyphen <= secondLast + 1 || lastHyphen >= code.length() - 1) {
            return false;
        }
        String segment = code.substring(secondLast + 1, lastHyphen);
        String serial = code.substring(lastHyphen + 1);
        return segment.matches("\\d{1,2}") && serial.matches("\\d{1,4}");
    }

    /** 取 JSON 字符串字段值（限信封字段面，不做通用 JSON 解析）。 */
    private static String extractString(String body, String field) {
        String needle = "\"" + field + "\"";
        int idx = body.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colon = body.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return null;
        }
        int start = body.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = body.indexOf('"', start + 1);
        return end < 0 ? null : body.substring(start + 1, end);
    }

    private static String readBody(Response response) {
        Response.Body body = response.body();
        if (body == null) {
            return "";
        }
        try (Reader reader = body.asReader(StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[512];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
                if (text.length() >= BODY_LIMIT_BYTES) {
                    return text.substring(0, BODY_LIMIT_BYTES);
                }
            }
            return text.toString();
        } catch (IOException ex) {
            return "";
        }
    }
}
