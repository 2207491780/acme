package com.acme.platform.system.api;

import java.util.Set;

/**
 * 登录认证画像（架构设计 3.3 / 4.2，FR-001）：platform-auth 登录链路只读查询，
 * 由服务身份内部令牌调用（Q-D08）；按 username 全局定位、@IgnoreTenant 平台级查询（12.4.2）。
 *
 * @param userId   用户 ID（字符串化雪花 ID）
 * @param username 用户名（全局唯一，登录定位依据）
 * @param nickname 昵称（签发响应展示用）
 * @param tenantId 租户标识（token tenantId claim 来源）
 * @param status   账号状态（DISABLED 时登录拒绝）
 * @param roles    角色编码集（token roles claim 来源）
 */
public record AuthProfileView(String userId, String username, String nickname, String tenantId,
                              String status, Set<String> roles) {

    public AuthProfileView {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
