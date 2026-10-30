package com.acme.framework.jpa.support;

import com.acme.framework.jpa.internal.AuditSupport;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * 审计基类（FR-008/009/010，12.2 能力交付物）：雪花主键（12.3.1 禁自增）、审计字段、乐观锁版本、软删除标记。
 *
 * <p>查询过滤（FR-009 默认过滤已删除记录）经 {@code @SQLRestriction} 落基类（适配点核验：Hibernate 7
 * EntityBinder 支持 @MappedSuperclass 上的 @SQLRestriction）；删除=置标记由 {@link SoftDelete} 标注的实体
 * 经 internal 适配层改写（架构设计 6.3）。</p>
 */
@MappedSuperclass
@SQLRestriction("deleted = false")
public abstract class AuditBase {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 64, nullable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 64, nullable = false)
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted;

    /** 雪花主键赋值 + 审计缺省填充（12.3.1、6.2 缺省口径：操作人永不缺失，缺失不得导致持久化失败）。 */
    @PrePersist
    void onPersist() {
        if (id == null) {
            id = AuditSupport.nextId();
        }
        if (deleted == null) {
            deleted = Boolean.FALSE;
        }
        String operator = AuditSupport.operator();
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (createdBy == null) {
            createdBy = operator;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (updatedBy == null) {
            updatedBy = operator;
        }
    }

    /** 更新时维护审计字段（FR-008）。 */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        updatedBy = AuditSupport.operator();
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Long getVersion() {
        return version;
    }

    public Boolean getDeleted() {
        return deleted;
    }
}
