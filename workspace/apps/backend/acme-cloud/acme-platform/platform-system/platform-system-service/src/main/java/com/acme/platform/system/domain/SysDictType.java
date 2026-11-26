package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.DictKind;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 字典类型（7.2.6：租户归属，(tenant_id, dict_code) 唯一；软删除）。
 * REQ-20261125-230807 增补 dictKind（V35 列，FLAT 默认|TREE；存量行经 ADD COLUMN DEFAULT 全量归
 * FLAT，平铺行为零变化；形态创建时定，修改接口不提供 kind 变更）。
 */
@Table("sys_dict_type")
public class SysDictType extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("dict_code")
    private String dictCode;

    @Column("dict_name")
    private String dictName;

    @Column("dict_kind")
    private DictKind dictKind;

    @Column("status")
    private EnableStatus status;

    @Column("remark")
    private String remark;

    protected SysDictType() {
    }

    public SysDictType(String dictCode, String dictName, String remark) {
        this(dictCode, dictName, remark, DictKind.FLAT);
    }

    /** REQ-20261125-230807：kind 显式创建（null 归一 FLAT）。 */
    public SysDictType(String dictCode, String dictName, String remark, DictKind dictKind) {
        this.dictCode = dictCode;
        this.dictName = dictName;
        this.dictKind = dictKind == null ? DictKind.FLAT : dictKind;
        this.status = EnableStatus.ENABLED;
        this.remark = remark;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getDictCode() {
        return dictCode;
    }

    public String getDictName() {
        return dictName;
    }

    public void setDictName(String dictName) {
        this.dictName = dictName;
    }

    public DictKind getDictKind() {
        return dictKind;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
