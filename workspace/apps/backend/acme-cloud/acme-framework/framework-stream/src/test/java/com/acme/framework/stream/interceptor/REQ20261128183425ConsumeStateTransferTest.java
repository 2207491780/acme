package com.acme.framework.stream.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.framework.stream.config.StreamProperties;
import com.acme.framework.stream.interceptor.ConsumeChannelInterceptor.PipelineState;
import com.acme.framework.stream.internal.DedupStore;
import com.acme.framework.stream.support.IdempotentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费状态跨线程派发探针（REQ-20261127-161433 P2-22 AC）：preSend（线程 A）→ 函数包装器
 * 跳过判定/收尾（线程 B/C 异步派发形态）全链经**消息头状态**承载——不同线程读取均生效，
 * ThreadLocal 仅作非 Message 函数形态回退（探针线程 B 不设 ThreadLocal 即证明头面自足）。
 */
class REQ20261128183425ConsumeStateTransferTest {

    static final class ProbeStore implements DedupStore {

        final List<String> claimed = new CopyOnWriteArrayList<>();
        final List<String> done = new CopyOnWriteArrayList<>();
        final List<String> failed = new CopyOnWriteArrayList<>();

        @Override
        public Claim tryBegin(String key, Duration processingTtl) {
            claimed.add(key);
            return Claim.DUPLICATE;
        }

        @Override
        public void markDone(String key, Duration doneTtl) {
            done.add(key);
        }

        @Override
        public void markFailed(String key) {
            failed.add(key);
        }
    }

    private final ProbeStore store = new ProbeStore();
    private final StreamProperties properties = new StreamProperties();
    private final ConsumeChannelInterceptor interceptor = new ConsumeChannelInterceptor(
            new IdempotentGuard(store, new com.acme.framework.core.support.KeyComposer("demo"), properties),
            properties);

    private Message<String> inbound(String key) {
        return MessageBuilder.withPayload("payload")
                .setHeader(ServiceHeaders.MESSAGE_ID, "m-probe-1")
                .setHeader(ServiceHeaders.IDEMPOTENT_KEY, key)
                .build();
    }

    @Test
    void stateTravelsViaMessageHeaderAcrossThreadHandoff() throws Exception {
        Message<String> inbound = inbound("ik-probe-1");
        AtomicReference<Message<?>> afterPreSend = new AtomicReference<>();
        // 线程 A：preSend（通道拦截器线程）
        CompletableFuture.runAsync(() -> afterPreSend.set(interceptor.preSend(inbound, null))
                , probePool()).get(5, TimeUnit.SECONDS);
        assertThat(afterPreSend.get()).isNotNull();

        // 线程 B：函数包装器跳过判定——不设任何 ThreadLocal，仅凭消息头状态（DUPLICATE→skip）
        AtomicReference<Boolean> skipOnThreadB = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
                    PipelineState state = ConsumeChannelInterceptor.stateOf(afterPreSend.get());
                    skipOnThreadB.set(state != null && state.skip());
                }, probePool()).get(5, TimeUnit.SECONDS);
        assertThat(skipOnThreadB.get()).as("线程 B 无 ThreadLocal：头面状态判定 skip=true（DUPLICATE）")
                .isTrue();

        // 线程 C：收尾（afterSendCompletion 头面读取 → markDone... DUPLICATE 不标记，仅关作用域）
        CompletableFuture.runAsync(() -> interceptor.afterSendCompletion(
                afterPreSend.get(), null, true, null), probePool()).get(5, TimeUnit.SECONDS);
        // DUPLICATE 跳过路径：不去重键标记/释放（skip=true 短路），收尾无异常即通过
        assertThat(store.done).isEmpty();
        assertThat(store.failed).isEmpty();
    }

    @Test
    void pipelineWrapperSkipsViaHeaderStateOnForeignThread() throws Exception {
        Message<String> inbound = inbound("ik-probe-2");
        Message<?> afterPreSend = interceptor.preSend(inbound, null);

        // 模拟 SCSt 函数包装器在派发线程收到 Message 形态输入：头面判定跳过
        CopyOnWriteArrayList<Object> executed = new CopyOnWriteArrayList<>();
        ConsumePipeline.SkippingConsumer<Object> wrapper = new ConsumePipeline.SkippingConsumer<>(executed::add);

        CompletableFuture.runAsync(() -> wrapper.accept(afterPreSend), probePool()).get(5, TimeUnit.SECONDS);
        assertThat(executed).as("DUPLICATE 消息经头面状态在异线程干净跳过（业务不执行）").isEmpty();
    }

    private static ExecutorService pool;

    private static synchronized ExecutorService probePool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(3);
        }
        return pool;
    }
}
