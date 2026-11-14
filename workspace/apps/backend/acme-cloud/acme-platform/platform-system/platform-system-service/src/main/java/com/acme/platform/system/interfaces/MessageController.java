package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.context.UserContext;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminMessageSend;
import com.acme.platform.system.api.dto.AdminMessageView;
import com.acme.platform.system.application.MessageUseCase;
import com.acme.platform.system.domain.SysMessage;
import com.acme.platform.system.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内信接口（REQ-20261114-120102：路径与契约 §API 逐字一致）。
 * 收件箱 / 未读数 / 标记已读均以当前主体为收件人语义（me：UserContext.userId）。
 */
@RestController
@RequestMapping("/admin/v1/system/messages")
public class MessageController {

    private final MessageUseCase messageUseCase;

    public MessageController(MessageUseCase messageUseCase) {
        this.messageUseCase = messageUseCase;
    }

    @PostMapping
    @RequirePermission(SystemPermissions.MESSAGE_SEND)
    public List<AdminMessageView> send(@Valid @RequestBody AdminMessageSend request) {
        return messageUseCase.send(request).stream().map(EntityViews::messageView).toList();
    }

    @GetMapping
    @RequirePermission(SystemPermissions.MESSAGE_LIST)
    public PageResult<AdminMessageView> inbox(@RequestParam(defaultValue = "1") Integer page,
                                              @RequestParam(defaultValue = "20") Integer size,
                                              @RequestParam(required = false) Boolean isRead) {
        PageResult<SysMessage> result =
                messageUseCase.inbox(currentRecipientId(), isRead, new PageQuery(page, size));
        return new PageResult<>(
                result.records().stream().map(EntityViews::messageView).toList(), result.total());
    }

    /** 未读数（确认记录⑩：Result data=裸数字；int 出参不受全局 Long→String wire 规约影响）。 */
    @GetMapping("/unread-count")
    @RequirePermission(SystemPermissions.MESSAGE_LIST)
    public int unreadCount() {
        return messageUseCase.unreadCount(currentRecipientId());
    }

    @PutMapping("/read-all")
    @RequirePermission(SystemPermissions.MESSAGE_READ)
    public void readAll() {
        messageUseCase.markAllRead(currentRecipientId());
    }

    @PutMapping("/{messageId}/read")
    @RequirePermission(SystemPermissions.MESSAGE_READ)
    public AdminMessageView markRead(@PathVariable Long messageId) {
        return EntityViews.messageView(messageUseCase.markRead(currentRecipientId(), messageId));
    }

    /** 当前主体（String userId → Long；解析失败归 0=系统主体，收件箱恒空 fail-closed）。 */
    private static Long currentRecipientId() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            return 0L;
        }
        try {
            return Long.valueOf(context.userId());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
