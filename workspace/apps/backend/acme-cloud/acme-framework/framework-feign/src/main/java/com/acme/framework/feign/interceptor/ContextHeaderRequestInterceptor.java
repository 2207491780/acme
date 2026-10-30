package com.acme.framework.feign.interceptor;

import com.acme.framework.core.support.ContextHeaders;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * 上下文头注入（FR-023/AC-023、BR-014）：经 core {@link ContextHeaders} 取三上下文，缺值不注入。
 */
public class ContextHeaderRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        ContextHeaders.outbound().forEach((name, value) -> template.header(name, value));
    }
}
