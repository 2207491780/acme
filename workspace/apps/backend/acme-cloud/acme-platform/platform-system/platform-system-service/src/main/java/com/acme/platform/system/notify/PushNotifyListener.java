package com.acme.platform.system.notify;

import com.acme.framework.core.support.AfterCommitEventListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * APP 推送旁路监听器（REQ-20261119-082531 确认记录⑥，TL 决策②解耦案）：
 * 复用站内信既有 {@link NotifyPushEvent} 事务提交后事件（framework-core AfterCommitEventListener
 * 惯例）——NotifyPushBus 扇出 WS 帧之外旁路调用 {@link ExpoPushSender}（开关关闭/bean 缺席时
 * 零行为，ObjectProvider 兜底）。失败不向发布方传播（BR-019，dispatcher 逐监听隔离兜底）。
 */
@Component
public class PushNotifyListener implements AfterCommitEventListener<NotifyPushEvent> {

    private final ObjectProvider<ExpoPushSender> senders;

    public PushNotifyListener(ObjectProvider<ExpoPushSender> senders) {
        this.senders = senders;
    }

    @Override
    public void onEvent(NotifyPushEvent event) {
        if (event == null || event.recipientId() == null) {
            return;
        }
        ExpoPushSender sender = senders.getIfAvailable();
        if (sender == null) {
            // 推送开关关闭（默认态）：零行为（确认记录⑥）
            return;
        }
        sender.send(event.recipientId(), event.title());
    }
}
