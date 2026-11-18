package com.acme.platform.auth.api;

/**
 * 用户令牌类型（FR-003 基线）：ACCESS 全端签发；REFRESH 仅 C 端签发
 * （11.4：C「令牌 + 刷新」多端并存 + 一次性轮换；B 端短会话重登不提供刷新，Q-003）。
 */
public enum AuthTokenType {

    /** 访问令牌。 */
    ACCESS("access"),

    /** 刷新令牌（C 端；仅用于刷新端点，不得鉴权 API——网关 AuthFilter 拒绝）。 */
    REFRESH("refresh");

    private final String wireName;

    AuthTokenType(String wireName) {
        this.wireName = wireName;
    }

    /** 协议名（与网关 tokenType claim 校验对齐）。 */
    public String wireName() {
        return wireName;
    }
}
