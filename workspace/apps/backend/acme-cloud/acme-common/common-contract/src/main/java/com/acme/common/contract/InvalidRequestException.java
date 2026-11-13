package com.acme.common.contract;

/**
 * 协议基元参数越界异常（review 修复）：分页等协议类型的入参不合法时抛出，
 * 由 framework-web 全局映射为 400（此前抛裸 IllegalArgumentException 落未知异常 500，
 * 客户端误传被误判为服务端错误）。contract 层不依赖 common-core，故独立成类、不带 ErrorCode。
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
