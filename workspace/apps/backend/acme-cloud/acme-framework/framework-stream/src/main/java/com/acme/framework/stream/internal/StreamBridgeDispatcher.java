package com.acme.framework.stream.internal;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

import java.util.function.Supplier;

/**
 * StreamBridge 分发实现（FR-016 按绑定路由；binder 由使用方引入，8.6）。
 */
public final class StreamBridgeDispatcher implements MessageDispatcher {

    private final Supplier<StreamBridge> streamBridge;

    public StreamBridgeDispatcher(Supplier<StreamBridge> streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Override
    public boolean send(String binding, Message<?> message) {
        return streamBridge.get().send(binding, message);
    }
}
