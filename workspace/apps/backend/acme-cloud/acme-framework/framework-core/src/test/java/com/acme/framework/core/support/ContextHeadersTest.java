package com.acme.framework.core.support;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.ClientContext;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站约定头映射测试（FR-023、11.6.3）：身份 / 租户 / 端别 / 语言随上下文注入，缺值不注入。
 */
class ContextHeadersTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
        ClientContext.clear();
        LocaleContext.clear();
    }

    @Test
    void localeHeaderInjectedFromLocaleContext() {
        LocaleContext.bind(new LocaleContext(Locale.forLanguageTag("zh-CN")));
        Map<String, String> headers = ContextHeaders.outbound();
        assertThat(headers).containsEntry(ServiceHeaders.LOCALE, "zh-CN");
    }

    @Test
    void localeAbsentWhenUnboundOrBlank() {
        Map<String, String> headers = ContextHeaders.outbound();
        assertThat(headers).doesNotContainKey(ServiceHeaders.LOCALE);
    }
}
