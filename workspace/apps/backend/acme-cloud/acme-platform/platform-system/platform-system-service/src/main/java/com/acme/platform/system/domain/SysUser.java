package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.framework.jpa.support.SoftDelete;
import com.acme.platform.system.api.EnableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

/**
 * 用户（7.2.2：租户归属实体，@TenantId 自动注入与过滤 12.4.1；username 全局唯一=登录定位依据；
 * @SoftDelete 软删除）。
 */
@Entity
@SoftDelete
@Table(name = "sys_user", indexes = @Index(name = "idx_sys_user_tenant", columnList = "tenant_id"))
public class SysUser extends AuditBase {

    @TenantId
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "username", length = 64, nullable = false, unique = true)
    private String username;

    @Column(name = "nickname", length = 64, nullable = false)
    private String nickname;

    @Column(name = "email", length = 128)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EnableStatus status;

    @Column(name = "built_in", nullable = false)
    private Boolean builtIn;

    protected SysUser() {
    }

    public SysUser(String username, String nickname, String email, String phone, Boolean builtIn) {
        this.username = username;
        this.nickname = nickname;
        this.email = email;
        this.phone = phone;
        this.status = EnableStatus.ENABLED;
        this.builtIn = builtIn == null || builtIn;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }

    public Boolean getBuiltIn() {
        return builtIn;
    }
}
