package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.framework.jpa.support.SoftDelete;
import com.acme.platform.system.api.EnableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

/**
 * 字典项（7.2.6：租户归属，(tenant_id, dict_type_id, item_value) 唯一；dictType 为同 schema 从属关联
 * （唯一 JPA 对象关联，不使用级联 / orphanRemoval——生命周期由应用层用例控制）；@SoftDelete 软删除）。
 */
@Entity
@SoftDelete
@Table(name = "sys_dict_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_sys_dict_item_type_value",
                columnNames = {"tenant_id", "dict_type_id", "item_value"}),
        indexes = @Index(name = "idx_sys_dict_item_type", columnList = "dict_type_id"))
public class SysDictItem extends AuditBase {

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dict_type_id", nullable = false)
    private SysDictType dictType;

    @Column(name = "item_label", length = 128, nullable = false)
    private String itemLabel;

    @Column(name = "item_value", length = 128, nullable = false)
    private String itemValue;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EnableStatus status;

    protected SysDictItem() {
    }

    public SysDictItem(SysDictType dictType, String itemLabel, String itemValue, Integer sortOrder) {
        this.dictType = dictType;
        this.itemLabel = itemLabel;
        this.itemValue = itemValue;
        this.sortOrder = sortOrder == null ? 0 : sortOrder;
        this.status = EnableStatus.ENABLED;
    }

    public String getTenantId() {
        return tenantId;
    }

    public SysDictType getDictType() {
        return dictType;
    }

    public Long getDictTypeId() {
        return dictType == null ? null : dictType.getId();
    }

    public String getItemLabel() {
        return itemLabel;
    }

    public void setItemLabel(String itemLabel) {
        this.itemLabel = itemLabel;
    }

    public String getItemValue() {
        return itemValue;
    }

    public void setItemValue(String itemValue) {
        this.itemValue = itemValue;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }
}
