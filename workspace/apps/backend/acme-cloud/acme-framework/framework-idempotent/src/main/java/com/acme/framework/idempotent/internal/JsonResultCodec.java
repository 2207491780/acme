package com.acme.framework.idempotent.internal;

import com.acme.common.serialization.Json;

/**
 * Json 门面编解码（2.5.1 统一 Module 集，framework-stream resolveJson 先例）。
 */
public class JsonResultCodec implements ResultCodec {

    private final Json json;

    public JsonResultCodec(Json json) {
        this.json = json;
    }

    @Override
    public String encode(Object value) {
        return json.toJson(value);
    }

    @Override
    public <T> T decode(String json, Class<T> type) {
        return this.json.fromJson(json, type);
    }
}
