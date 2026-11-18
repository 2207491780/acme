package com.acme.framework.core.support;

import com.acme.common.context.Channel;

/**
 * 头解析值载体（Q-D04 下沉共享值对象）：framework-web 解析约定头后暂存于请求属性，
 * framework-security 校验通过后以其构造 {@link com.acme.common.context.ClientContext} 写入。
 *
 * <p>仅暂存解析值，不做信任决策（Q-005、BR-007/008）。</p>
 */
public record ClientContextDraft(Channel channel, String deviceId, String appVersion) {

    /** 请求属性暂存位（Q-D04；BR-025 请求结束清理）。 */
    public static final String ATTRIBUTE_NAME = ClientContextDraft.class.getName() + ".DRAFT";
}
