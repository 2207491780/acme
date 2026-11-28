package com.acme.framework.idempotent.internal;

import com.acme.common.core.BizException;
import com.acme.common.serialization.Json;
import com.acme.framework.idempotent.config.IdempotentProperties;
import com.acme.framework.idempotent.support.IdempotentError;
import com.acme.framework.idempotent.support.InFlightPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261127-235505 B2a（P1-02/P2-17）幂等门 complete 失败 / DUPLICATE 空结果分支单测
 * （构造照抄既有 {@link HttpIdempotentGateTest}：{@link InMemoryIdempotentRecordStore}
 * + {@link JsonResultCodec} + {@link IdempotentProperties}，inFlightWait=300ms；
 * 纯 JUnit5+AssertJ，无 Spring 上下文）。
 *
 * <p>AC→用例映射（既有回归引用见交付映射表）：</p>
 * <ul>
 *   <li>AC-5：action 成功但 store.markDone 抛异常 → BizException(STORE_UNAVAILABLE)、
 *       认领不释放（记录维持 P）、action 恰执行一次。</li>
 *   <li>AC-6：complete 失败后同 key 重试 → BizException(IN_FLIGHT / SYS-11-0002)，
 *       不重执行（action 计数不变）。</li>
 *   <li>AC-7：记录 s=D 但 findResult=null → BizException(STORE_UNAVAILABLE / SYS-11-0003)，
 *       绝不静默返回 null（fail-closed）。</li>
 *   <li>AC-8：业务合法返回 null（encode 落库 JSON "null" 字面量）→ 首次返回 null、
 *       重复回放 null，不落入 AC-7 分支（合法 null 回放回归护栏）。</li>
 *   <li>AC-9 复证：action 抛异常 → 认领释放、同 key 重试可重执行（同语义
 *       {@link HttpIdempotentGateTest#failureReleasesClaimAndAllowsRetry}）。</li>
 *   <li>AC-10 复证：tryBegin 抛异常 → STORE_UNAVAILABLE fail-closed（同语义
 *       {@link HttpIdempotentGateTest#storeFailureIsFailClosed}）。</li>
 * </ul>
 */
class REQ20261127235505HttpIdempotentGateCompleteFailureTest {

    private final InMemoryIdempotentRecordStore store = new InMemoryIdempotentRecordStore();
    private final ResultCodec codec = new JsonResultCodec(new Json(JsonMapper.builder().build()));
    private final HttpIdempotentGate gate = new HttpIdempotentGate(store, codec, properties());

    private static IdempotentProperties properties() {
        IdempotentProperties properties = new IdempotentProperties();
        properties.setInFlightWait(Duration.ofMillis(300));
        return properties;
    }

    /** markDone 恒抛的坏 store（AC-5/AC-6：complete 失败不释放认领）。 */
    private InMemoryIdempotentRecordStore markDoneBrokenStore() {
        return new InMemoryIdempotentRecordStore() {
            @Override
            public void markDone(String key, String resultJson, Duration resultTtl) {
                throw new IllegalStateException("markDone down");
            }
        };
    }

    // ---------- AC-5：complete 失败 → STORE_UNAVAILABLE，action 恰执行一次 ----------

    @Test
    void should_throw_store_unavailable_and_execute_action_once_when_mark_done_fails() {
        InMemoryIdempotentRecordStore brokenStore = markDoneBrokenStore();
        HttpIdempotentGate brokenGate = new HttpIdempotentGate(brokenStore, codec, properties());
        AtomicInteger calls = new AtomicInteger();
        Callable<String> action = () -> {
            calls.incrementAndGet();
            return "ok";
        };

        assertThatThrownBy(() -> brokenGate.execute("req235505-cf", action, String.class,
                InFlightPolicy.WAIT))
                .as("complete 失败应对调用方抛 BizException(STORE_UNAVAILABLE)")
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.STORE_UNAVAILABLE));
        assertThat(calls.get()).as("action 应恰执行一次").isEqualTo(1);
    }

    // ---------- AC-6：complete 失败后重试 → IN_FLIGHT，不重执行 ----------

    @Test
    void should_reject_in_flight_without_reexecution_when_retrying_after_complete_failure() {
        InMemoryIdempotentRecordStore brokenStore = markDoneBrokenStore();
        HttpIdempotentGate brokenGate = new HttpIdempotentGate(brokenStore, codec, properties());
        AtomicInteger calls = new AtomicInteger();
        Callable<String> action = () -> {
            calls.incrementAndGet();
            return "ok";
        };

        // given：首次 execute 因 complete 失败抛 STORE_UNAVAILABLE，记录维持 P 未释放
        assertThatThrownBy(() -> brokenGate.execute("req235505-cf-retry", action, String.class,
                InFlightPolicy.WAIT))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.STORE_UNAVAILABLE));
        assertThat(calls.get()).as("前置：action 应恰执行一次").isEqualTo(1);

        // when/then：claimProcessingTtl 内同 key 重试 → IN_FLIGHT（SYS-11-0002），不重执行
        assertThatThrownBy(() -> brokenGate.execute("req235505-cf-retry", action, String.class,
                InFlightPolicy.WAIT))
                .as("complete 失败后同 token 重试应得 BizException(IN_FLIGHT)")
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.IN_FLIGHT));
        assertThat(calls.get()).as("重试不得重执行 action（P 未释放）").isEqualTo(1);
    }

    // ---------- AC-7：s=D + findResult=null → STORE_UNAVAILABLE，绝不返回 null ----------

    @Test
    void should_throw_store_unavailable_when_duplicate_record_has_missing_result() {
        String key = "req235505-dnull";
        // 造 s=D + result 缺失态（绕过 gate：store 直写 markDone(key, null, ttl)）
        store.tryBegin(key, Duration.ofSeconds(60));
        store.markDone(key, null, Duration.ofMinutes(5));

        assertThatThrownBy(() -> gate.execute(key, () -> "x", String.class, InFlightPolicy.WAIT))
                .as("DUPLICATE + findResult=null 应 fail-closed 抛 BizException(STORE_UNAVAILABLE)，"
                        + "绝不静默返回 null")
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.STORE_UNAVAILABLE));
    }

    // ---------- AC-8：合法 null 结果回放护栏 ----------

    @Test
    void should_replay_null_result_when_action_legitimately_returns_null() {
        AtomicInteger calls = new AtomicInteger();
        Callable<String> nullAction = () -> {
            calls.incrementAndGet();
            return null;
        };
        String key = "req235505-nullok";

        assertThat(gate.execute(key, nullAction, String.class, InFlightPolicy.WAIT))
                .as("首次执行合法 null 应原样返回 null").isNull();
        assertThat(gate.execute(key, nullAction, String.class, InFlightPolicy.WAIT))
                .as("重复执行应回放缓存 null（encode(null)=JSON \"null\" 字面量，不落入缺失态分支）")
                .isNull();
        assertThat(calls.get()).as("重复执行应回放而非重执行").isEqualTo(1);
    }

    // ---------- AC-9 复证：action 失败释放认领、重试可重执行 ----------

    @Test
    void should_release_claim_and_allow_retry_when_action_fails() {
        AtomicInteger calls = new AtomicInteger();
        Callable<String> flaky = () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return "recovered";
        };
        String key = "req235505-retry";

        assertThatThrownBy(() -> gate.execute(key, flaky, String.class, InFlightPolicy.WAIT))
                .as("action 首败应原样上抛").isInstanceOf(IllegalStateException.class);
        assertThat(gate.execute(key, flaky, String.class, InFlightPolicy.WAIT))
                .as("action 失败释放认领后，同 key 重试应可成功重执行").isEqualTo("recovered");
        assertThat(calls.get()).as("重试应重执行 action（计数=2）").isEqualTo(2);
    }

    // ---------- AC-10 复证：tryBegin 失败 fail-closed ----------

    @Test
    void should_fail_closed_when_try_begin_fails() {
        IdempotentRecordStore broken = new InMemoryIdempotentRecordStore() {
            @Override
            public Claim tryBegin(String key, Duration processingTtl) {
                throw new IllegalStateException("redis down");
            }
        };
        HttpIdempotentGate failClosed = new HttpIdempotentGate(broken, codec, properties());

        assertThatThrownBy(() -> failClosed.execute("req235505-beginfail", () -> "x",
                String.class, InFlightPolicy.WAIT))
                .as("tryBegin 失败应 STORE_UNAVAILABLE fail-closed")
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.STORE_UNAVAILABLE));
    }
}
