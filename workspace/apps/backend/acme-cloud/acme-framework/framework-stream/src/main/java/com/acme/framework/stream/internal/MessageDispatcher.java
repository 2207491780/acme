package com.acme.framework.stream.internal;

import org.springframework.messaging.Message;

/**
 * 发送分发面（internal）：隔离 StreamBridge 具体形态，供 MessageSender 与测试替换。
 */
public interface MessageDispatcher {

    /** 投递到目标绑定；未被接收返回 false。 */
    boolean send(String binding, Message<?> message);
}
