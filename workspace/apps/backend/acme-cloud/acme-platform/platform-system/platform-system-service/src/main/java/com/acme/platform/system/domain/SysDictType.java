package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 字典类型（7.2.6：租户归属，(tenant_id, dict_code) 唯一；软删除）。
 */
@Table("sys_dict_type")
public class SysDictType extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("dict_code")
    private String dictCode;

    @Column("dict_name")
    private String dictName;

    @Column("status")
    private EnableStatus status;

    @Column("remark")
    private String remark;

    protected SysDictType() {
    }

    public SysDictType(String dictCode, String dictName, String remark) {
        this.dictCode = dictCode;
        this.dictName = dictName;
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
