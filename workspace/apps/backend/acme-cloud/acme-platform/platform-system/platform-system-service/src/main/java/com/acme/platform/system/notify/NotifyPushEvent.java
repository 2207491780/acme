package com.acme.platform.system.notify;

/**
 * 站内信推送事件（REQ-20261114-120102）：用例在事务内发布，事务提交后由
 * {@link NotifyPushBus}（AfterCommitEventListener 惯例）扇出到 Redis pub/sub。
 */
public record NotifyPushEvent(Long recipientId, Long messageId, String title) {
}
