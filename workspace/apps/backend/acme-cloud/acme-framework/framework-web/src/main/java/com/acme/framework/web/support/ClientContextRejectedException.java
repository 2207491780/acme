package com.acme.framework.web.support;

/**
 * REJECT 策略下端别声明非法（FR-007 异常分支，5.4）；由提取过滤器转标准失败响应 400。
 */
public class ClientContextRejectedException extends RuntimeException {

    public ClientContextRejectedException(String message) {
        super(message);
    }
}
