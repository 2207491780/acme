package com.acme.framework.web.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.common.core.ErrorCode;
import com.acme.common.core.Result;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.web.event.OpExceptionCapturedEvent;
import com.acme.framework.web.support.WebError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常捕获事件发布测试（REQ-20261117-061017 确认①，AC-1/AC-2 单元面）：
 * 500 路径发布事件（上下文/请求/堆栈截断随事件）、BizException/4xx 不发、开关关不发、
 * 发布失败/无出口静默（不影响响应）。
 */
class GlobalExceptionTranslatorEventTest {

    /** 无 PromptLocalizer 的 ObjectProvider 桩件（Spring 7 全默认方法，仅需 getIfAvailable）。 */
    private static ObjectProvider<PromptLocalizer> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public PromptLocalizer getIfAvailable() {
                return null;
            }
        };
    }

    /** 录制型事件出口桩件。 */
    private static ObjectProvider<ApplicationEventPublisher> providerOf(ApplicationEventPublisher publisher) {
        return new ObjectProvider<>() {
            @Override
            public ApplicationEventPublisher getIfAvailable() {
                return publisher;
            }
        };
    }

    private final List<OpExceptionCapturedEvent> events = new ArrayList<>();

    /** 录制出口（ApplicationEventPublisher.publishEvent(Object) 入参宽型，窄化入列）。 */
    private void record(Object event) {
        if (event instanceof OpExceptionCapturedEvent captured) {
            events.add(captured);
        }
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        TenantContext.clear();
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private GlobalExceptionTranslator translator(boolean enabled) {
        return new GlobalExceptionTranslator(emptyProvider(), providerOf(this::record),
                "platform-demo", enabled);
    }

    /** 500 路径发布事件：service/上下文/请求信息随事件，statusCode=500。 */
    @Test
    void should_publishEventWithCapturedContext_when_unexpected500() {
        TraceContext.bind(new TraceContext("trace-e1", "span-e1"));
        TenantContext.bind(new TenantContext("t-9"));
        UserContext.bind(new UserContext("op-7", Set.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/infra/files");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ResponseEntity<Result<Object>> response =
                translator(true).handleUnexpected(new IllegalStateException("boom-e1"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(events).hasSize(1);
        OpExceptionCapturedEvent event = events.getFirst();
        assertThat(event.service()).isEqualTo("platform-demo");
        assertThat(event.traceId()).isEqualTo("trace-e1");
        assertThat(event.spanId()).isEqualTo("span-e1");
        assertThat(event.tenantId()).isEqualTo("t-9");
        assertThat(event.operatorId()).isEqualTo("op-7");
        assertThat(event.method()).isEqualTo("POST");
        assertThat(event.path()).isEqualTo("/admin/v1/infra/files");
        assertThat(event.statusCode()).isEqualTo(500);
        assertThat(event.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(event.message()).isEqualTo("boom-e1");
        assertThat(event.stackSummary()).contains("boom-e1");
        assertThat(event.occurredAt()).isNotNull();
    }

    /** DataAccessException 还原失败的 500 路径同样发布事件（确认①两类之一）。 */
    @Test
    void should_publishEvent_when_dataAccessWithoutBizCauseMaps500() {
        DataAccessException wrapped = new DataAccessException("sql-data-access", new IllegalStateException("jdbc-x")) {
        };
        translator(true).handleDataAccess(wrapped);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().message()).contains("sql-data-access");
        assertThat(events.getFirst().stackSummary())
                .as("堆栈摘要含外层异常首行（printStackTrace 形态）")
                .isNotNull()
                .contains("sql-data-access");
    }

    /** BizException（业务失败）不发布事件（确认①/AC-2）。 */
    @Test
    void should_notPublishEvent_when_bizException() {
        translator(true).handleBiz(new BizException(WebError.CONCURRENCY_CONFLICT));
        assertThat(events).isEmpty();
    }

    /** 4xx 参数校验类路径不发布事件（确认①/AC-2）。 */
    @Test
    void should_notPublishEvent_when_validationFailure4xx() {
        GlobalExceptionTranslator translator = translator(true);
        translator.handleBadRequest(new IllegalStateException("x"));
        assertThat(events).isEmpty();
    }

    /** 开关关闭（acme.exception-log.enabled=false）不发布（确认①）。 */
    @Test
    void should_notPublishEvent_when_switchDisabled() {
        translator(false).handleUnexpected(new IllegalStateException("boom"));
        assertThat(events).isEmpty();
    }

    /** 无事件出口（ObjectProvider 空）静默——响应不受影响。 */
    @Test
    void should_return500Silently_when_noEventPublisherAvailable() {
        GlobalExceptionTranslator translator = new GlobalExceptionTranslator(emptyProvider(),
                new ObjectProvider<>() {
                    @Override
                    public ApplicationEventPublisher getIfAvailable() {
                        return null;
                    }
                }, "platform-demo", true);
        ResponseEntity<Result<Object>> response =
                translator.handleUnexpected(new IllegalStateException("boom"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo(CommonError.INTERNAL_ERROR.code());
        assertThat(events).isEmpty();
    }

    /** 发布器抛出异常静默（不影响 500 响应主路径，确认①发布失败不外抛）。 */
    @Test
    void should_return500Silently_when_publisherThrows() {
        ApplicationEventPublisher brokenPublisher = event -> {
            throw new IllegalStateException("publish-broken");
        };
        GlobalExceptionTranslator translator = new GlobalExceptionTranslator(emptyProvider(),
                providerOf(brokenPublisher), "platform-demo", true);
        ResponseEntity<Result<Object>> response =
                translator.handleUnexpected(new IllegalStateException("boom"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo(CommonError.INTERNAL_ERROR.code());
    }

    /** 堆栈摘要在发布侧截断 8KB（确认①）。 */
    @Test
    void should_truncateStackSummary_when_stackExceedsLimit() {
        translator(true).handleUnexpected(new IllegalStateException("A".repeat(20_000)));
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().stackSummary().length())
                .as("发布侧堆栈截断 8KB（8192 字符）")
                .isEqualTo(GlobalExceptionTranslator.STACK_SUMMARY_MAX_LENGTH);
    }

    /** 无上下文/非 Web 语境留空（确认① try-closed）。 */
    @Test
    void should_leaveContextFieldsNull_when_noBoundContext() {
        translator(true).handleUnexpected(new IllegalStateException("boom"));
        assertThat(events).hasSize(1);
        OpExceptionCapturedEvent event = events.getFirst();
        assertThat(event.traceId()).isNull();
        assertThat(event.tenantId()).isNull();
        assertThat(event.operatorId()).isNull();
        assertThat(event.method()).isNull();
        assertThat(event.path()).isNull();
    }

    /** 遗留一参构造（既有测试面）不发布：无事件出口即无事件。 */
    @Test
    void should_notPublishEvent_withLegacySingleArgConstructor() {
        GlobalExceptionTranslator translator = new GlobalExceptionTranslator(emptyProvider());
        ResponseEntity<Result<Object>> response =
                translator.handleUnexpected(new IllegalStateException("boom"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(events).isEmpty();
    }

    /** HttpStatusAware 错误码映射语义回归护栏（BizException 不发事件）。 */
    @Test
    void bizExceptionWithHttpStatusStillMapsStatus() {
        ErrorCodeWith403 error = new ErrorCodeWith403();
        ResponseEntity<Result<Object>> response =
                translator(true).handleBiz(new BizException(error));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(events).isEmpty();
    }

    private static final class ErrorCodeWith403 implements ErrorCode, HttpStatusAware {
        @Override
        public String code() {
            return "SYS-test-e001";
        }

        @Override
        public String defaultMessage() {
            return "无权访问";
        }

        @Override
        public int httpStatus() {
            return 403;
        }
    }
}
