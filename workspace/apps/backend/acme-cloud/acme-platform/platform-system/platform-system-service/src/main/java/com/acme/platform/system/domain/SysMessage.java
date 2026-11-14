package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 站内信（REQ-20261114-120102：租户归属实体；公告发布扇出与单发共用水箱，
 * 收件人可见（收件箱按 recipient 过滤）；is_read/read_at 标记已读语义）。
 */
@Table("sys_message")
public class SysMessage extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("sender_id")
    private Long senderId;

    @Column("recipient_id")
    private Long recipientId;

    @Column("title")
    private String title;

    @Column("content")
    private String content;

    @Column("is_read")
    private Boolean isRead;

    @Column("read_at")
    private Instant readAt;

    protected SysMessage() {
    }

    public SysMessage(Long senderId, Long recipientId, String title, String content) {
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.title = title;
        this.content = content;
        this.isRead = Boolean.FALSE;
    }

    /** 已读置位模板（read-all 批量 updateByQuery 用：仅收件状态字段参与 SET，其余列保持 NULL 不覆盖）。 */
    public static SysMessage readTemplate(Instant readAt) {
        SysMessage message = new SysMessage();
        message.isRead = Boolean.TRUE;
        message.readAt = readAt;
        return message;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }
}
