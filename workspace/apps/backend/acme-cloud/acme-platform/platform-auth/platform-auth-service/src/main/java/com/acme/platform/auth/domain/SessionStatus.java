package com.acme.platform.auth.domain;

/**
 * 会话状态（架构设计 7.3.2）：ACTIVE→KICKED（互踢 / 重置密码撤销）/ LOGOUT（主动登出）/ EXPIRED（预留终态，清理任务第 12 章）。
 */
public enum SessionStatus {

    /** 活跃。 */
    ACTIVE,

    /** 被互踢 / 管理撤销。 */
    KICKED,

    /** 主动登出。 */
    LOGOUT,

    /** 已过期（预留）。 */
    EXPIRED
}
