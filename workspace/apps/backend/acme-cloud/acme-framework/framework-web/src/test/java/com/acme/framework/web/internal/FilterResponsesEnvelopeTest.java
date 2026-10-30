package com.acme.framework.web.internal;

import com.acme.common.core.CommonError;
import com.acme.common.core.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过滤器信封与 {@link Result} 序列化形状契约锁（10.5.3、Q-D11）：
 * 手写信封字段必须与 JsonMapper 序列化 Result 的字段集/取值一致，防止形状漂移。
 */
class FilterResponsesEnvelopeTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void filterFailureEnvelopeMatchesResultShape() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterResponses.writeFailure(response, 400, "SYS-01-0002", "请求参数不合法");

        JsonNode filterBody = mapper.readTree(response.getContentAsString());
        JsonNode resultBody = mapper.readTree(mapper.writeValueAsString(Result.fail(CommonError.NOT_FOUND)));

        assertThat(fieldsOf(filterBody))
                .as("信封字段集必须与 Result 序列化一致")
                .isEqualTo(fieldsOf(resultBody))
                .containsExactlyInAnyOrder("success", "code", "message", "data");
        assertThat(filterBody.get("success").asBoolean()).isFalse();
        assertThat(filterBody.get("code").asString()).isEqualTo("SYS-01-0002");
        assertThat(filterBody.get("message").asString()).isEqualTo("请求参数不合法");
        assertThat(filterBody.get("data").isNull()).isTrue();
    }

    @Test
    void writeFailureEscapesMessageSpecials() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterResponses.writeFailure(response, 500, "SYS-00-0006", "line1\n\"quoted\"\\end");

        JsonNode filterBody = mapper.readTree(response.getContentAsString());
        assertThat(filterBody.get("message").asString()).isEqualTo("line1\n\"quoted\"\\end");
        assertThat(filterBody.get("code").asString()).isEqualTo("SYS-00-0006");
        assertThat(filterBody.get("data").isNull()).isTrue();
    }

    private static Set<String> fieldsOf(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }
}
