package com.acme.common.serialization;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Json 门面（FR-027；Modules.md 2.5.3）。内部统一捕获 JacksonException 并转为 {@link BizException}，
 * 不抛受检异常、不外抛原始 JacksonException（BR-011）。本类是本模块唯一引用 common-core 的类型（BR-002）。
 * JsonMapper 由 Boot 4 / framework-jackson 装配注入（2.5.2）；common 无 Bean 注解（BR-019）。
 *
 * <p>异常转换行为（DP-011 定稿）：编解码失败统一转 {@code BizException(SYS-00-0007)}，
 * 原始 JacksonException 保留为 cause；行为由 JsonTest 锁定。方法集定稿为
 * {@link #toJson} / {@link #fromJson}；泛型集合等复杂类型由调用方持 JsonMapper 处理（2.5.1 统一装配）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class Json {

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper 不能为空");
        }
        this.mapper = mapper;
    }

    /** 序列化为 JSON 文本（FR-027）；编解码失败一律转 {@link BizException}（BR-011；DP-011 定稿）。 */
    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BizException(JsonErrorCode.CODEC, null, e);
        }
    }

    /** 反序列化为对象（FR-027）；编解码失败一律转 {@link BizException}（BR-011；DP-011 定稿）。 */
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new BizException(JsonErrorCode.CODEC, null, e);
        }
    }

    /** Json 编解码错误码承载（段位表见 ErrorCodeLayout：SYS-00=common 共享，序号 0007 接续）。 */
    private record JsonErrorCode(String code, String defaultMessage) implements ErrorCode {

        static final ErrorCode CODEC = new JsonErrorCode("SYS-00-0007", "JSON 编解码失败");
    }
}
