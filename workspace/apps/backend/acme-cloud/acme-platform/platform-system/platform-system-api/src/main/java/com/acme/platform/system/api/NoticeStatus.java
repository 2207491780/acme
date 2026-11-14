package com.acme.platform.system.api;

/**
 * 通知公告状态（REQ-20261114-120102 消息通知闭环一期；确认记录⑤）：按枚举名序列化 / 反序列化，
 * 与 {@link EnableStatus} 同 wire 约定（不得添加 code() 方法，EnumCodeModule 识别约定，Modules.md 2.5）。
 * 生命周期：草稿（可编辑可删除）→ 发布（扇出全员站内信；已发布禁改禁删=NOTICE_PUBLISHED，重复发布幂等）
 * → 下线（OFFLINE：可编辑可删除可再发布，再发布=重新扇出新发布周期）。
 */
public enum NoticeStatus {

    /** 草稿。 */
    DRAFT,

    /** 已发布。 */
    PUBLISHED,

    /** 已下线（确认记录⑤：PUBLISHED→OFFLINE 幂等；OFFLINE 后可编辑/可删除/可再发布）。 */
    OFFLINE
}
