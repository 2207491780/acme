package com.acme.platform.infra.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 平台文件记录（REQ-20261114-071607 §变更清单 sys_file：bucket/object_key/original_name/size/
 * content_type/biz_type/biz_id/deleted/tenant_id/上传人 + 审计基类口径）：
 * 软删语义（删除=置 deleted 标记 + storage.remove，FR-009）；落库租户经 {@code TenantContext}
 * 自动注入（12.4.1）；保留期清理按 updated_at 判定（无独立 deleted_at 列的软删时间代理口径）。
 */
@Table("sys_file")
public class SysFile extends AuditBase {

    @Column("bucket")
    private String bucket;

    /** 对象键完整路径（namespace/yyyy/MM/dd/name，framework-storage ObjectKey 口径）。 */
    @Column("object_key")
    private String objectKey;

    @Column("original_name")
    private String originalName;

    @Column("size")
    private Long size;

    @Column("content_type")
    private String contentType;

    @Column("biz_type")
    private String bizType;

    @Column("biz_id")
    private String bizId;

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    /** 上传人（UserContext.userId；后台/系统上传可空）。 */
    @Column("uploaded_by")
    private String uploadedBy;

    protected SysFile() {
    }

    public SysFile(String bucket, String objectKey, String originalName, Long size,
                   String contentType, String bizType, String bizId) {
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.originalName = originalName;
        this.size = size;
        this.contentType = contentType;
        this.bizType = bizType;
        this.bizId = bizId;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public Long getSize() {
        return size;
    }

    public String getContentType() {
        return contentType;
    }

    public String getBizType() {
        return bizType;
    }

    public String getBizId() {
        return bizId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }
}
