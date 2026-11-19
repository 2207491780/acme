package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * APP 推送令牌（REQ-20261119-082531 确认记录⑤）：Expo 令牌按 token 全局唯一（同一设备重装 /
 * 换账号注册按 upsert 归一），user_id + platform 归属标注；无租户列（用户域数据 Q-D04 口径）。
 * 删除走物理删（FlexDeletes.forceDeleteByQuery 旁路）：软删行占 token 唯一键致重注册冲突，
 * 对齐 sys_user_role 绑定表口径。
 */
@Table("sys_push_token")
public class SysPushToken extends AuditBase {

    @Column("user_id")
    private Long userId;

    @Column("token")
    private String token;

    @Column("platform")
    private String platform;

    protected SysPushToken() {
    }

    public SysPushToken(Long userId, String token, String platform) {
        this.userId = userId;
        this.token = token;
        this.platform = platform;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }
}
