package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.PermissionType;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 权限点（7.2.4：平台级目录表，无 tenant_id、不参与租户过滤——全租户授权基线，Q-D04；软删除）。
 */
@Table("sys_permission")
public class SysPermission extends AuditBase {

    @Column("permission_code")
    private String permissionCode;

    @Column("permission_name")
    private String permissionName;

    @Column("permission_type")
    private PermissionType permissionType;

    @Column("http_method")
    private String httpMethod;

    @Column("http_path")
    private String httpPath;

    @Column("status")
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
