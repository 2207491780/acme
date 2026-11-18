package com.acme.framework.security.support;

/**
 * 内部令牌类型声明（FR-015、7.7 claims tokenType）。
 */
public enum TokenType {

    /** 访问令牌。 */
    ACCESS("access"),

    /** 刷新令牌（当访问令牌用→拒绝，FR-015）。 */
    REFRESH("refresh");

    private final String wireName;

    TokenType(String wireName) {
        this.wireName = wireName;
    }

    /** 协议名（7.7）。 */
    public String wireName() {
        return wireName;
    }

    /** 按协议名解析；未知返回 null。 */
    public static TokenType parse(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (TokenType type : values()) {
            if (type.wireName.equalsIgnoreCase(wireName.trim())) {
                return type;
            }
        }
        return null;
    }
}
