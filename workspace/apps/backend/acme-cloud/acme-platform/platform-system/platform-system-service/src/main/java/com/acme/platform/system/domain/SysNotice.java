package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.NoticeStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 通知公告（REQ-20261114-120102：租户归属实体；status DRAFT→PUBLISHED，发布扇出全员站内信；
 * 已发布禁改禁删=NOTICE_PUBLISHED，重复发布幂等不重复扇出）。
 */
@Table("sys_notice")
public class SysNotice extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("title")
    private String title;

    @Column("content")
    private String content;

    @Column("status")
    private NoticeStatus status;

    @Column("publish_at")
    private Instant publishAt;

    protected SysNotice() {
    }

    public SysNotice(String title, String content) {
        this.title = title;
        this.content = content;
        this.status = NoticeStatus.DRAFT;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public NoticeStatus getStatus() {
        return status;
    }

    public void setStatus(NoticeStatus status) {
        this.status = status;
    }

    public Instant getPublishAt() {
        return publishAt;
    }

    public void setPublishAt(Instant publishAt) {
        this.publishAt = publishAt;
    }
}
