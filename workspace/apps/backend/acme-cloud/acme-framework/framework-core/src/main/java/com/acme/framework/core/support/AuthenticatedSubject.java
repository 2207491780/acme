package com.acme.framework.core.support;

import com.acme.common.context.Channel;

import java.util.Set;

/**
 * 已认证主体摘要（Q-D04 下沉共享值对象）：framework-security 校验通过后回写请求属性，
 * 供 framework-web 访问日志取用（FR-006、取舍 #7）。
 */
public record AuthenticatedSubject(String userId, String tenantId, Channel channel, Set<String> roles) {

    /** 请求属性回写位（Q-D04；BR-025 请求结束清理）。 */
    public static final String ATTRIBUTE_NAME = AuthenticatedSubject.class.getName() + ".SUBJECT";

    public AuthenticatedSubject {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
