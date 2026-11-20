package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 服务能力（REQ-20261121-054613 确认②：平台级目录表，无 tenant_id、不参与租户过滤——
 * sys_menu/sys_permission 同款 Q-D04 口径；软删除；uk(capability_code) 由 DDL 承载）。
 * 一期=套餐级分配（租户生效=绑定套餐的能力集），目录行本身无租户归属。
 */
@Table("sys_capability")
public class SysCapability extends AuditBase {

    @Column("capability_code")
    private String capabilityCode;

    @Column("capability_name")
    private String capabilityName;

    @Column("status")
    private EnableStatus status;

    protected SysCapability() {
    }

    public SysCapability(String capabilityCode, String capabilityName) {
        this.capabilityCode = capabilityCode;
        this.capabilityName = capabilityName;
        this.status = EnableStatus.ENABLED;
    }

    public String getCapabilityCode() {
        return capabilityCode;
    }

    public String getCapabilityName() {
        return capabilityName;
    }

    public void setCapabilityName(String capabilityName) {
        this.capabilityName = capabilityName;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }
}
