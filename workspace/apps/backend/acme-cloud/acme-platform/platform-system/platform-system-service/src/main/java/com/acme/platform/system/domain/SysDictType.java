package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.framework.jpa.support.SoftDelete;
import com.acme.platform.system.api.EnableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

/**
 * 字典类型（7.2.6：租户归属，(tenant_id, dict_code) 唯一；@SoftDelete 软删除）。
 */
@Entity
@SoftDelete
@Table(name = "sys_dict_type", uniqueConstraints = @UniqueConstraint(name = "uk_sys_dict_type_tenant_code",
        columnNames = {"tenant_id", "dict_code"}))
public class SysDictType extends AuditBase {

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "dict_code", length = 64, nullable = false)
    private String dictCode;

    @Column(name = "dict_name", length = 64, nullable = false)
    private String dictName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EnableStatus status;

    @Column(name = "remark", length = 255)
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
