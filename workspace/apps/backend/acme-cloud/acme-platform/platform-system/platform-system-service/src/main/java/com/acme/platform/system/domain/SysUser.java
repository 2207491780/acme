package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.api.EnableStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 用户（7.2.2：租户归属实体，租户列自动注入与过滤 12.4.1；username 全局唯一=登录定位依据；软删除）。
 * REQ-20261114-120104：dept_id / position_id 部门岗位归属（可空——存量用户不归属，语义不变）。
 * REQ-20261119-082531：avatar_url 头像地址（可空；文件服务 URL 或相对地址，个人中心资料卡消费）。
 */
@Table("sys_user")
public class SysUser extends AuditBase {

    @Column(value = "tenant_id", tenantId = true)
    private String tenantId;

    @Column("username")
    private String username;

    @Column("nickname")
    private String nickname;

    @Column("email")
    private String email;

    @Column("phone")
    private String phone;

    @Column("status")
    private EnableStatus status;

    @Column("built_in")
    private Boolean builtIn;

    @Column("dept_id")
    private Long deptId;

    @Column("position_id")
    private Long positionId;

    @Column("avatar_url")
    private String avatarUrl;

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

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public Long getPositionId() {
        return positionId;
    }

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }
}
