package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.TransactionEventPublisher;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.dto.AdminMessageSend;
import com.acme.platform.system.domain.SysMessage;
import com.acme.platform.system.infrastructure.repository.SysMessageRepository;
import com.acme.platform.system.notify.NotifyPushEvent;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 站内信用例（REQ-20261114-120102）：发送指定用户 / 收件箱分页（isRead 筛选）/ 未读数 /
 * 单条已读 / 全部已读。可见性=收件人语义（AC-3：非收件人不可见）；非本人消息操作=MESSAGE_FORBIDDEN
 * （AC-5），不存在=MESSAGE_NOT_FOUND。推送与公告扇出共用同一管道（落库后事务后事件 → NotifyPushBus）。
 */
@Service
public class MessageUseCase {

    /** 无上下文主体兜底：sender_id 恒非空，0 表示系统行为。 */
    private static final long SYSTEM_SENDER_ID = 0L;

    private final SysMessageRepository messageRepository;
    private final TransactionEventPublisher eventPublisher;

    public MessageUseCase(SysMessageRepository messageRepository, TransactionEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public List<SysMessage> send(AdminMessageSend request) {
        long senderId = currentSenderId();
        List<SysMessage> messages = request.recipientIds().stream()
                .distinct()
                .map(recipientId -> new SysMessage(senderId, recipientId, request.title(), request.content()))
                .toList();
        messageRepository.insertAll(messages);
        // 落库后发推送事件：事务内缓冲，提交后经 NotifyPushBus 扇出（AC-7 端到端 <1s）
        for (SysMessage message : messages) {
            eventPublisher.publish(new NotifyPushEvent(message.getRecipientId(), message.getId(), message.getTitle()));
        }
        return messages;
    }

    /** 我的收件箱分页（isRead 筛选：null=全部，true=已读，false=未读）。 */
    public PageResult<SysMessage> inbox(Long recipientId, Boolean isRead, PageQuery query) {
        QueryWrapper wrapper = QueryWrapper.create()
                .where(SysMessage::getRecipientId).eq(recipientId);
        if (isRead != null) {
            wrapper.and(SysMessage::getIsRead).eq(isRead);
        }
        wrapper.orderBy(SysMessage::getId, false);
        return messageRepository.findAll(wrapper, query.page(), query.size());
    }

    /** 未读数（int 形态：全局 Long→String wire 规约外（确认记录⑩ data=裸数字），计数上限富余）。 */
    public int unreadCount(Long recipientId) {
        return Math.toIntExact(messageRepository.countUnreadByRecipient(recipientId));
    }

    /** 标记单条已读（幂等：已读消息重复标记返回原状，不刷新 read_at）。 */
    @Transactional
    public SysMessage markRead(Long recipientId, Long messageId) {
        SysMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BizException(SystemError.MESSAGE_NOT_FOUND));
        if (!recipientId.equals(message.getRecipientId())) {
            throw new BizException(SystemError.MESSAGE_FORBIDDEN);
        }
        if (!Boolean.TRUE.equals(message.getIsRead())) {
            message.setIsRead(Boolean.TRUE);
            message.setReadAt(Instant.now());
            return messageRepository.save(message);
        }
        return message;
    }

    /** 全部已读：返回置为已读的消息条数。 */
    @Transactional
    public int markAllRead(Long recipientId) {
        return messageRepository.markAllReadByRecipient(recipientId, Instant.now());
    }

    /** 当前主体 id（上下文缺失 / 非数值兜底系统主体 0：收件箱恒空、操作恒 MESSAGE_NOT_FOUND，fail-closed）。 */
    private static long currentSenderId() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            return SYSTEM_SENDER_ID;
        }
        try {
            return Long.parseLong(context.userId());
        } catch (NumberFormatException ex) {
            return SYSTEM_SENDER_ID;
        }
    }
}
