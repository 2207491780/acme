package com.acme.platform.system.domain;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.framework.jpa.support.SoftDelete;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.PermissionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * 权限点（7.2.4：平台级目录表，无 tenant_id、不参与租户过滤——全租户授权基线，Q-D04；
 * @SoftDelete 软删除）。
 */
@Entity
@SoftDelete
@Table(name = "sys_permission")
public class SysPermission extends AuditBase {

    @Column(name = "permission_code", length = 128, nullable = false, unique = true)
    private String permissionCode;

    @Column(name = "permission_name", length = 64, nullable = false)
    private String permissionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_type", length = 16, nullable = false)
    private PermissionType permissionType;

    @Column(name = "http_method", length = 16)
    private String httpMethod;

    @Column(name = "http_path", length = 255)
    private String httpPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EnableStatus status;

    protected SysPermission() {
    }

    public SysPermission(String permissionCode, String permissionName, PermissionType permissionType,
                         String httpMethod, String httpPath) {
        this.permissionCode = permissionCode;
        this.permissionName = permissionName;
        this.permissionType = permissionType;
        this.httpMethod = httpMethod;
        this.httpPath = httpPath;
        this.status = EnableStatus.ENABLED;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public PermissionType getPermissionType() {
        return permissionType;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getHttpPath() {
        return httpPath;
    }

    public void setHttpPath(String httpPath) {
        this.httpPath = httpPath;
    }

    public EnableStatus getStatus() {
        return status;
    }

    public void setStatus(EnableStatus status) {
        this.status = status;
    }
}
