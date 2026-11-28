package com.acme.platform.workflow;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.common.core.Result;
import com.acme.framework.feign.support.FeignError;
import com.acme.framework.feign.support.RemoteCallException;
import com.acme.platform.scheduler.api.OneShotJobRegisterRequest;
import com.acme.platform.scheduler.api.OneShotJobRegisteredView;
import com.acme.platform.scheduler.api.SchedulerJobClient;
import com.acme.platform.workflow.infrastructure.client.SchedulerRegistrationClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261127-235505 B2a workflow 侧 {@link SchedulerRegistrationClient} 单测
 * （纯 JUnit5+AssertJ+手写 fake，不启 Spring 上下文；被测构造器
 * SchedulerRegistrationClient(ObjectProvider&lt;SchedulerJobClient&gt;)，
 * fake 实现 com.acme.platform.scheduler.api.SchedulerJobClient 两方法，
 * ObjectProvider 匿名类仅实现 getObject()）。
 *
 * <p>AC→用例映射：</p>
 * <ul>
 *   <li>AC-3：注销请求命中 HTTP 404（异常携带状态码）→ 按状态码判定收敛为幂等成功，
 *       不因消息字符串误判；非 404（500）失败照常上抛且为原异常实例；无 HTTP 状态的
 *       BizException 不得吞。</li>
 *   <li>裁决③（契约确认⑥③）：register 撞键按结构化 errorCode().code() 判定收敛——
 *       code=platform-scheduler-01-0001 收敛不抛；其他 code 上抛；仅 message 文本含锚点
 *       而 code 不同者仍上抛（message 字符串匹配已被移除的反例复证）。</li>
 * </ul>
 */
class REQ20261127235505SchedulerRegistrationClientTest {

    /** 裁决③锚点常量字面量（不 import 常量类造依赖）。 */
    private static final String SCHEDULER_JOB_KEY_DUPLICATE = "platform-scheduler-01-0001";

    // ---------- 手写测试替身（无 Mockito 依赖） ----------

    /** 恒抛注入失败的 SchedulerJobClient 假件：register/unregister 失败可分别注入。 */
    private static final class FailingJobClient implements SchedulerJobClient {

        private final RuntimeException registerFailure;
        private final RuntimeException unregisterFailure;

        FailingJobClient(RuntimeException registerFailure, RuntimeException unregisterFailure) {
            this.registerFailure = registerFailure;
            this.unregisterFailure = unregisterFailure;
        }

        @Override
        public Result<OneShotJobRegisteredView> registerOneShot(OneShotJobRegisterRequest request) {
            throw registerFailure;
        }

        @Override
        public Result<Void> deleteByJobKey(String jobKey) {
            throw unregisterFailure;
        }
    }

    /** 匿名 ObjectProvider：仅实现 getObject()（Spring 7 全 default 方法）。 */
    private static ObjectProvider<SchedulerJobClient> providerOf(SchedulerJobClient client) {
        return new ObjectProvider<>() {
            @Override
            public SchedulerJobClient getObject() {
                return client;
            }
        };
    }

    /** 测试内自定义 ErrorCode record（实现 com.acme.common.core.ErrorCode 抽象方法）。 */
    private record FixedError(String codeValue, String messageValue) implements ErrorCode {

        @Override
        public String code() {
            return codeValue;
        }

        @Override
        public String defaultMessage() {
            return messageValue;
        }
    }

    private SchedulerRegistrationClient clientFailingOnRegister(RuntimeException failure) {
        return new SchedulerRegistrationClient(providerOf(new FailingJobClient(failure, null)));
    }

    private SchedulerRegistrationClient clientFailingOnUnregister(RuntimeException failure) {
        return new SchedulerRegistrationClient(providerOf(new FailingJobClient(null, failure)));
    }

    private static void register(SchedulerRegistrationClient client, String jobKey) {
        client.register(jobKey, Instant.now().plusSeconds(3600), "req235505-timer");
    }

    // ---------- AC-3：404 按状态码判定收敛为幂等成功 ----------

    @Test
    void should_swallow_not_found_as_idempotent_success_when_unregister_carries_404_status() {
        SchedulerRegistrationClient client = clientFailingOnUnregister(
                new RemoteCallException(FeignError.CALL_FAILED, 404));

        assertThatCode(() -> client.unregister("wf-timer:req235505-1"))
                .as("异常携带 HTTP 404 应收敛为幂等成功，不抛")
                .doesNotThrowAnyException();
    }

    // ---------- AC-3：非 404 失败照常上抛且为原异常实例 ----------

    @Test
    void should_rethrow_same_exception_when_unregister_fails_with_non_404_status() {
        RemoteCallException failure = new RemoteCallException(FeignError.CALL_FAILED, 500);
        SchedulerRegistrationClient client = clientFailingOnUnregister(failure);

        assertThatThrownBy(() -> client.unregister("wf-timer:req235505-2"))
                .as("HTTP 500 失败应原样上抛（不得吞为收敛）")
                .isSameAs(failure);
    }

    // ---------- AC-3：无 HTTP 状态的 BizException 不得吞 ----------

    @Test
    void should_rethrow_stateless_biz_exception_when_unregister_carries_no_http_status() {
        BizException failure = new BizException(FeignError.CALL_FAILED);
        SchedulerRegistrationClient client = clientFailingOnUnregister(failure);

        assertThatThrownBy(() -> client.unregister("wf-timer:req235505-3"))
                .as("无状态 BizException 应上抛（不得吞）")
                .isSameAs(failure);
    }

    // ---------- 裁决③：撞键结构化判定收敛（errorCode().code() 相等才收敛） ----------

    @Test
    void should_converge_idempotently_when_register_fails_with_scheduler_duplicate_key_code() {
        SchedulerRegistrationClient client = clientFailingOnRegister(
                new BizException(new FixedError(SCHEDULER_JOB_KEY_DUPLICATE, "任务键已注册")));

        assertThatCode(() -> register(client, "wf-timer:req235505-4"))
                .as("errorCode.code()=platform-scheduler-01-0001 应幂等收敛不抛")
                .doesNotThrowAnyException();
    }

    // ---------- 裁决③反例 a：其他调度错误码上抛 ----------

    @Test
    void should_rethrow_when_register_fails_with_other_scheduler_error_code() {
        BizException failure = new BizException(
                new FixedError("platform-scheduler-01-0002", "其他调度错误"));
        SchedulerRegistrationClient client = clientFailingOnRegister(failure);

        assertThatThrownBy(() -> register(client, "wf-timer:req235505-5"))
                .as("errorCode.code() 非 platform-scheduler-01-0001 应上抛")
                .isSameAs(failure);
    }

    // ---------- 裁决③反例 b：仅 message 含锚点、code 不同 → 仍上抛（字符串匹配已移除） ----------

    @Test
    void should_rethrow_when_message_contains_duplicate_code_but_error_code_differs() {
        BizException failure = new BizException(new FixedError("wf-timer-99-0001",
                "远端文案误含锚点 platform-scheduler-01-0001 不应被采信"));
        SchedulerRegistrationClient client = clientFailingOnRegister(failure);

        assertThatThrownBy(() -> register(client, "wf-timer:req235505-6"))
                .as("仅 message 文本含锚点而 errorCode 不同应仍上抛（弃字符串匹配）")
                .isSameAs(failure);
    }
}
