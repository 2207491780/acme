package com.acme.framework.web.config;

/**
 * 端别声明非法时的处理策略（FR-007，架构设计 5.4）。
 */
public enum ClientContextInvalidPolicy {

    /** 丢弃非法值 + WARN，继续处理（缺省）。 */
    IGNORE,

    /** 拒绝请求（标准失败响应 400）。 */
    REJECT
}
