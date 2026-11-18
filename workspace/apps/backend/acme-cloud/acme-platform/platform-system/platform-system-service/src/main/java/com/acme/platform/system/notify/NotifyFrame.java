package com.acme.platform.system.notify;

/**
 * WS 下行事件帧（REQ-20261114-120102 契约帧形态：{@code {"type":"message:new","messageId","title"}}）。
 */
public record NotifyFrame(String type, Long messageId, String title) {

    /** 站内信新增事件类型（契约 §API）。 */
    public static final String TYPE_MESSAGE_NEW = "message:new";

    public static NotifyFrame messageNew(Long messageId, String title) {
        return new NotifyFrame(TYPE_MESSAGE_NEW, messageId, title);
    }
}
