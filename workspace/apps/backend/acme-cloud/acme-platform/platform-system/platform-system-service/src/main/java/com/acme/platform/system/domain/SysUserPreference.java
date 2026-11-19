package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.system.infrastructure.mapper.JsonbTypeHandler;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 用户通知偏好（REQ-20261119-082531 确认记录⑤）：本人单行（user_id 全局唯一，无租户列——
 * 用户域数据按 user_id 定位，Q-D04 同 auth_session 口径）。
 * prefs 为 jsonb 列、实体侧 String 承载（{@link JsonbTypeHandler} 负责方言映射），
 * 载荷形态 {@code {"messagePush": boolean}}（UserPreferenceUseCase Prefs 载荷序列化）。
 */
@Table("sys_user_preference")
public class SysUserPreference extends AuditBase {

    @Column("user_id")
    private Long userId;

    @Column(value = "prefs", typeHandler = JsonbTypeHandler.class)
    private String prefs;

    protected SysUserPreference() {
    }

    public SysUserPreference(Long userId, String prefs) {
        this.userId = userId;
        this.prefs = prefs;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPrefs() {
        return prefs;
    }

    public void setPrefs(String prefs) {
        this.prefs = prefs;
    }
}
