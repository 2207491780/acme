package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 租户（7.2.1：平台级表，不标租户列、不参与过滤——租户目录自身；无软删，停用代替删除 FR-007）。
 * REQ-20261117-025200 增补联系人 / 有效期三列（均可空；expiresAt 过期租户登录拒绝与下拉排除，确认记录③）。
 * REQ-20261121-022910 增补 package_id（可空=NULL 不受限；非空=菜单下发 ∩ 套餐菜单集，确认②读时过滤）。
 */
@Table("sys_tenant")
public class SysTenant extends AuditBase {

    @Column("tenant_code")
    private String tenantCode;

    @Column("tenant_name")
    private String tenantName;

    @Column("status")
    private EnableStatus status;

    @Column("remark")
    private String remark;

    /** 联系人姓名（可空；REQ-20261117-025200）。 */
    @Column("contact_name")
    private String contactName;

    /** 联系人电话（可空；REQ-20261117-025200）。 */
    @Column("contact_phone")
    private String contactPhone;

    /** 租户有效期（可空=长期；过期即不可登录 / 不可切换目标，REQ-20261117-025200）。 */
    @Column("expires_at")
    private Instant expiresAt;

    /** 绑定套餐 ID（可空=null 不受套餐限制；REQ-20261121-022910，逻辑校验无 FK，确认③）。 */
    @Column("package_id")
    private Long packageId;

    /** 父租户 ID（可空=null 顶层；逻辑引用 sys_tenant.id，无 FK，REQ-20261122-102954 确认②）。 */
    @Column("parent_id")
    private Long parentId;

    /** 层级（1=顶层，上限 3；物化列，一致性由改父/级联写保证，REQ-20261122-102954 确认②）。 */
    @Column("level")
    private Integer level;

    protected SysTenant() {
    }

    public SysTenant(String tenantCode, String tenantName, String remark) {
        this.tenantCode = tenantCode;
        this.tenantName = tenantName;
        this.status = EnableStatus.ENABLED;
        this.remark = remark;
        this.parentId = null;
        this.level = 1;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
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

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getPackageId() {
        return packageId;
    }

    public void setPackageId(Long packageId) {
        this.packageId = packageId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }
}
