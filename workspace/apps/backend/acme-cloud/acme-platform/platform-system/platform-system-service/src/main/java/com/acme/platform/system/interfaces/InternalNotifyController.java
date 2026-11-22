package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.api.dto.InternalNotifyMessageRequest;
import com.acme.platform.system.application.NoticeUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知中心内部消息端点（REQ-20261122-150756 确认④；不经网关对外，openapi 不登记）：
 * 跨服务调度告警经 POST /internal/v1/notify/messages 扇出全员站内信（复用
 * NoticeUseCase.publish 的既有管道：sys_message 批插 + 事务后 NotifyPushEvent →
 * Redis pub/sub → WS/Expo）。服务间鉴权对齐 internal 面（X-Acme-Token 内部令牌校验）。
 * 端点为共享资源预占：BPM-6 定时事件联动将复用（防漂移——路径/字段不得变更）。
 */
@RestController
public class InternalNotifyController {

    private final NoticeUseCase noticeUseCase;

    public InternalNotifyController(NoticeUseCase noticeUseCase) {
        this.noticeUseCase = noticeUseCase;
    }

    /** 内部告警消息全员扇出（站内信+WS+Expo 现有管道；sender/title 必填，content 可空）。 */
    @PostMapping("/internal/v1/notify/messages")
    public Result<Void> publish(@Valid @RequestBody InternalNotifyMessageRequest request) {
        noticeUseCase.publishInternalMessage(request.sender(), request.title(), request.content());
        return Result.ok(null);
    }
}
