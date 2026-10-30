package com.acme.framework.core.support;

/**
 * 错误码 HTTP 状态语义（架构设计 5.2「细分语义随错误码登记」）：框架侧错误枚举实现本接口登记状态，
 * framework-web / framework-gateway 统一映射时读取；未实现者默认 500（Q-D11 语义化状态码，
 * 未映射错误码不得默认 400）。
 */
public interface HttpStatusAware {

    /** 语义化 HTTP 状态码（Q-D11）。 */
    int httpStatus();
}
