package com.acme.platform.system.api;

/**
 * 服务间调用响应信封（framework-web 统一出参线上形态，10.5.3）：仅供 Feign 消费方解码；
 * 错误响应（非 2xx）经 framework-feign 错误解码为 BizException，不经本类型。
 *
 * @param <T> 业务数据类型
 */
public record ContractResult<T>(Boolean success, String code, String message, T data) {

    /** 取业务数据；非成功信封（理论不可达，防御式）显式失败。 */
    public T payload() {
        if (success == null || !success) {
            throw new IllegalStateException("非成功信封：" + code);
        }
        return data;
    }
}
