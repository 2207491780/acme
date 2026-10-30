package com.acme.framework.stream.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.stream.config.MissingIdempotentKeyPolicy;
import com.acme.framework.stream.config.StreamProperties;
import com.acme.framework.stream.internal.DedupStore;
import com.acme.framework.stream.support.IdempotentGuard;
import com.acme.framework.stream.support.StreamError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消费管线（FR-014/032、AC-015/036、M-12：三态幂等、上下文恢复与 finally 清理、失败释放进重试）。
 */
class ConsumeChannelInterceptorTest {

    static final class FakeStore implements DedupStore {

        final List<String> claimed = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        final AtomicReference<Claim> claimResult = new AtomicReference<>(Claim.CLAIMED);
        RuntimeException failure;

        @Override
        public Claim tryBegin(String key, Duration processingTtl) {
            if (failure != null) {
                throw failure;
            }
            claimed.add(key);
            return claimResult.get();
        }

        @Override
        public void markDone(String key, Duration doneTtl) {
            claimed.add("done:" + key);
        }

        @Override
        public void markFailed(String key) {
            released.add(key);
        }
    }

    private final FakeStore store = new FakeStore();
    private final StreamProperties properties = new StreamProperties();

    private ConsumeChannelInterceptor pipeline() {
        IdempotentGuard guard = new IdempotentGuard(store, new KeyComposer("demo"), properties);
        return new ConsumeChannelInterceptor(guard, properties);
    }

    private static Message<?> message(String idempotentKey) {
        return MessageBuilder.withPayload("body")
                .setHeader(ServiceHeaders.IDEMPOTENT_KEY, idempotentKey)
                .setHeader(ServiceHeaders.USER_ID, "u-1")
                .setHeader(ServiceHeaders.TENANT_ID, "t-1")
                .setHeader(ServiceHeaders.LOCALE, "zh-CN")
                .build();
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
        LocaleContext.clear();
    }

    // ---------- AC-015 / M-12 三态幂等 ----------

    @Test
    void duplicateDeliveryMarkedSkipForFunctionWrapper() {
        ConsumeChannelInterceptor pipeline = pipeline();
        store.claimResult.set(DedupStore.Claim.CLAIMED);
        assertThat(pipeline.preSend(message("k-1"), null)).isNotNull();
        pipeline.afterSendCompletion(message("k-1"), null, true, null);

        store.claimResult.set(DedupStore.Claim.DUPLICATE);
        assertThat(pipeline.preSend(message("k-1"), null))
                .as("重复投递不阻断投递（包装器跳过业务，AC-015）").isNotNull();
        assertThat(ConsumeChannelInterceptor.currentState().skip()).isTrue();
        pipeline.afterSendCompletion(message("k-1"), null, true, null);
    }

    @Test
    void inFlightDeliveryAlsoSkips() {
        ConsumeChannelInterceptor pipeline = pipeline();
        store.claimResult.set(DedupStore.Claim.IN_FLIGHT);
        assertThat(pipeline.preSend(message("k-inflight"), null)).isNotNull();
        assertThat(ConsumeChannelInterceptor.currentState().skip()).isTrue();
        pipeline.afterSendCompletion(message("k-inflight"), null, true, null);
    }

    @Test
    void functionWrapperSkipsBusinessOnDuplicate() {
        ConsumePipeline consumePipeline = new ConsumePipeline(new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.cloud.function.definition", "demo"));
        ConsumeChannelInterceptor pipeline = pipeline();
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Object> wrapped = (java.util.function.Consumer<Object>)
                consumePipeline.postProcessAfterInitialization(
                        (java.util.function.Consumer<Object>) arg -> ran.set(true), "demo");
        store.claimResult.set(DedupStore.Claim.DUPLICATE);
        pipeline.preSend(message("k-9"), null);
        wrapped.accept("payload");
        assertThat(ran).as("重复消息业务不执行（AC-015）").isFalse();
        pipeline.afterSendCompletion(message("k-9"), null, true, null);

        store.claimResult.set(DedupStore.Claim.CLAIMED);
        pipeline.preSend(message("k-10"), null);
        wrapped.accept("payload");
        assertThat(ran).isTrue();
        pipeline.afterSendCompletion(message("k-10"), null, true, null);
    }

    @Test
    void unboundFunctionBeanIsNotWrapped() {
        ConsumePipeline consumePipeline = new ConsumePipeline(new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.cloud.function.definition", "boundOnly"));
        java.util.function.Consumer<Object> plain = arg -> {
        };
        Object processed = consumePipeline.postProcessAfterInitialization(plain, "unboundFunction");
        assertThat(processed).as("非绑定函数 bean 不被包装").isSameAs(plain);
    }

    @Test
    void crashWithoutMarkDoneAllowsReclaimAfterTtl() {
        // 模拟崩溃：tryBegin 成功后既不 markDone 也不 markFailed → 占位残留；
        // 三态口径下占位为 P 短 TTL，过期后（存储层键消失）可再认领=CLAIMED（M-12 不丢首投）
        ConsumeChannelInterceptor pipeline = pipeline();
        store.claimResult.set(DedupStore.Claim.CLAIMED);
        pipeline.preSend(message("k-crash"), null);
        // 不调用 afterSendCompletion（崩溃）
        assertThat(store.claimed).contains("demo:m:dedup:k-crash");
        assertThat(store.released).isEmpty();

        // P 过期后重投：存储侧重新可认领（此处以 CLAIMED 表达“键已过期可再占位”）
        store.claimResult.set(DedupStore.Claim.CLAIMED);
        assertThat(pipeline.preSend(message("k-crash"), null)).isNotNull();
        assertThat(ConsumeChannelInterceptor.currentState().skip()).isFalse();
        pipeline.afterSendCompletion(message("k-crash"), null, true, null);
    }

    @Test
    void successMarksDone() {
        ConsumeChannelInterceptor pipeline = pipeline();
        pipeline.preSend(message("k-2"), null);
        pipeline.afterSendCompletion(message("k-2"), null, true, null);
        assertThat(store.claimed).contains("done:demo:m:dedup:k-2");
    }

    @Test
    void failureReleasesClaimForRetry() {
        ConsumeChannelInterceptor pipeline = pipeline();
        pipeline.preSend(message("k-3"), null);
        pipeline.afterSendCompletion(message("k-3"), null, false, new IllegalStateException("boom"));
        assertThat(store.released).contains("demo:m:dedup:k-3");
    }

    @Test
    void missingKeyRejectsByDefault() {
        Message<?> bare = MessageBuilder.withPayload("body").build();
        assertThatThrownBy(() -> pipeline().preSend(bare, null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(StreamError.ENVELOPE_INVALID));
    }

    @Test
    void missingKeyAcceptWithWarnPasses() {
        properties.getIdempotency().setMissingKeyPolicy(MissingIdempotentKeyPolicy.ACCEPT_WITH_WARN);
        Message<?> bare = MessageBuilder.withPayload("body")
                .setHeader(ServiceHeaders.MESSAGE_ID, "m-nokey")
                .build();
        assertThat(pipeline().preSend(bare, null)).isNotNull();
    }

    @Test
    void dedupStoreFailureFailsClosed() {
        store.failure = new IllegalStateException("redis down");
        assertThatThrownBy(() -> pipeline().preSend(message("k-4"), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(StreamError.DEDUP_STORE_UNAVAILABLE));
    }

    @Test
    void fallsBackToMessageIdHeader() {
        Message<?> message = MessageBuilder.withPayload("body")
                .setHeader(ServiceHeaders.MESSAGE_ID, "m-1")
                .build();
        assertThat(pipeline().preSend(message, null)).isNotNull();
        assertThat(store.claimed).contains("demo:m:dedup:m-1");
    }

    // ---------- 认领后中途异常释放占位（M-12） ----------

    @Test
    void preSendFailureAfterClaimReleasesPlaceholder() {
        // CHANNEL 头 toString 抛异常 → ConsumeContexts.restore 失败 → preSend catch → markFailed
        Message<?> broken = MessageBuilder.withPayload("body")
                .setHeader(ServiceHeaders.IDEMPOTENT_KEY, "k-orphan")
                .setHeader(ServiceHeaders.CHANNEL, new Object() {
                    @Override
                    public String toString() {
                        throw new IllegalStateException("header broken");
                    }
                })
                .build();
        assertThatThrownBy(() -> pipeline().preSend(broken, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.released).as("中途异常释放占位（M-12 防孤儿）").contains("demo:m:dedup:k-orphan");
    }

    // ---------- AC-036 上下文恢复 + finally 清理 ----------

    @Test
    void restoresContextsDuringBusinessAndCleansAfterwards() {
        ConsumeChannelInterceptor pipeline = pipeline();
        Message<?> message = message("k-5");
        pipeline.preSend(message, null);
        assertThat(UserContext.get()).isNotNull();
        assertThat(UserContext.get().userId()).isEqualTo("u-1");
        assertThat(TenantContext.get().tenantId()).isEqualTo("t-1");
        assertThat(LocaleContext.get()).isNotNull();
        assertThat(LocaleContext.get().locale()).isEqualTo(Locale.forLanguageTag("zh-CN"));
        pipeline.afterSendCompletion(message, null, true, null);
        assertThat(UserContext.get()).as("消费结束 finally 清理（AC-036）").isNull();
        assertThat(TenantContext.get()).isNull();
        assertThat(LocaleContext.get()).isNull();
    }

    @Test
    void cleansContextsOnFailurePath() {
        ConsumeChannelInterceptor pipeline = pipeline();
        Message<?> message = message("k-6");
        pipeline.preSend(message, null);
        pipeline.afterSendCompletion(message, null, false, new IllegalStateException("boom"));
        assertThat(UserContext.get()).isNull();
        assertThat(TenantContext.get()).isNull();
        assertThat(LocaleContext.get()).isNull();
    }
}
