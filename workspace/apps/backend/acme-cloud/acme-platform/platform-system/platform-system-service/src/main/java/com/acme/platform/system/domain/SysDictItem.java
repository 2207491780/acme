package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 字典项（7.2.6：租户归属，(tenant_id, dict_type_id, item_value) 唯一；dictTypeId 为同 schema 从属 id 引用
 * （不使用对象关联，不使用级联 / orphanRemoval——生命周期由应用层用例控制）；软删除）。
 */
@Table("sys_dict_item")
public class SysDictItem extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("dict_type_id")
    private Long dictTypeId;

    @Column("item_label")
    private String itemLabel;

    @Column("item_value")
    private String itemValue;

    @Column("sort_order")
    private Integer sortOrder;

    @Column("status")
    private EnableStatus status;

    protected SysDictItem() {
    }

    public SysDictItem(SysDictType dictType, String itemLabel, String itemValue, Integer sortOrder) {
        this.dictTypeId = dictType == null ? null : dictType.getId();
        this.itemLabel = itemLabel;
        this.itemValue = itemValue;
        this.sortOrder = sortOrder == null ? 0 : sortOrder;
        this.status = EnableStatus.ENABLED;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getDictTypeId() {
        return dictTypeId;
    }

    public void setDictTypeId(Long dictTypeId) {
        this.dictTypeId = dictTypeId;
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
