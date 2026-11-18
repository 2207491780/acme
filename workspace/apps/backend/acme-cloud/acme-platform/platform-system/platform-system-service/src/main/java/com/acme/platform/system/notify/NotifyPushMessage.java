package com.acme.platform.system.notify;

/**
 * Redis pub/sub 通道载荷（REQ-20261114-120102 多实例扇出；确认记录⑨钉死形态
 * {@code {userId, messageId, title}}）：userId 定向投递，订阅实例据此把 message:new 帧投递给
 * 本地持有该 userId 的会话（tester 侧 PSUBSCRIBE 捕获同形态）。
 */
public record NotifyPushMessage(Long userId, Long messageId, String title) {
}
