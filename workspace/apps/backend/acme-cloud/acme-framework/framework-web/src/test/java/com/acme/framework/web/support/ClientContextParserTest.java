package com.acme.framework.web.support;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.Channel;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.web.config.ClientContextInvalidPolicy;
import com.acme.framework.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ClientContext 解析测试（FR-007、5.4、AC-007）。
 */
class ClientContextParserTest {

    private ClientContextParser parser(ClientContextInvalidPolicy policy) {
        WebProperties properties = new WebProperties();
        properties.getClientContext().setInvalidPolicy(policy);
        return new ClientContextParser(properties);
    }

    @Test
    void parsesHeadersToDraft() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ServiceHeaders.CHANNEL, "B");
        request.addHeader(ServiceHeaders.DEVICE_ID, "device-1");
        request.addHeader(ServiceHeaders.APP_VERSION, "1.2.3");
        ClientContextDraft draft = parser(ClientContextInvalidPolicy.IGNORE).parse(request);
        assertThat(draft.channel()).isEqualTo(Channel.B);
        assertThat(draft.deviceId()).isEqualTo("device-1");
        assertThat(draft.appVersion()).isEqualTo("1.2.3");
    }

    @Test
    void missingHeadersYieldNullFields() {
        ClientContextDraft draft = parser(ClientContextInvalidPolicy.IGNORE).parse(new MockHttpServletRequest());
        assertThat(draft.channel()).isNull();
        assertThat(draft.deviceId()).isNull();
        assertThat(draft.appVersion()).isNull();
    }

    @Test
    void invalidChannelIgnoredWithPolicy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ServiceHeaders.CHANNEL, "X");
        ClientContextDraft draft = parser(ClientContextInvalidPolicy.IGNORE).parse(request);
        assertThat(draft.channel()).isNull();
    }

    @Test
    void invalidChannelRejectedWithPolicy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ServiceHeaders.CHANNEL, "X");
        assertThatThrownBy(() -> parser(ClientContextInvalidPolicy.REJECT).parse(request))
                .isInstanceOf(ClientContextRejectedException.class);
    }

    @Test
    void draftAttributeNameIsStable() {
        assertThat(ClientContextDraft.ATTRIBUTE_NAME).contains("DRAFT");
    }
}
