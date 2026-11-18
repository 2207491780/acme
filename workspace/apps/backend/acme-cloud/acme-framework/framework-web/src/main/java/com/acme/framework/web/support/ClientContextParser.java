package com.acme.framework.web.support;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.Channel;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.web.config.ClientContextInvalidPolicy;
import com.acme.framework.web.config.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求头 → {@link ClientContextDraft} 解析（FR-007、5.4）：仅提取解析与暂存，
 * 不做信任决策、不写 {@link com.acme.common.context.ClientContext}（Q-005、BR-007/008）。
 */
public class ClientContextParser {

    private static final Logger log = LoggerFactory.getLogger(ClientContextParser.class);

    private final ClientContextInvalidPolicy invalidPolicy;

    public ClientContextParser(WebProperties properties) {
        this.invalidPolicy = properties.getClientContext().getInvalidPolicy();
    }

    /**
     * 解析约定头（FR-007）；头缺失字段为 null；端别非法按策略处理（5.4）。
     *
     * @throws ClientContextRejectedException REJECT 策略下端别声明非法
     */
    public ClientContextDraft parse(HttpServletRequest request) {
        String rawChannel = header(request, ServiceHeaders.CHANNEL);
        Channel channel = parseChannel(rawChannel);
        return new ClientContextDraft(channel,
                header(request, ServiceHeaders.DEVICE_ID),
                header(request, ServiceHeaders.APP_VERSION));
    }

    private Channel parseChannel(String rawChannel) {
        if (rawChannel == null || rawChannel.isBlank()) {
            return null;
        }
        try {
            return Channel.valueOf(rawChannel.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            if (invalidPolicy == ClientContextInvalidPolicy.REJECT) {
                throw new ClientContextRejectedException("端别声明非法：" + rawChannel);
            }
            log.warn("端别声明非法已丢弃（FR-007）：{}", rawChannel);
            return null;
        }
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
