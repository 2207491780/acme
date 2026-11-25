package com.acme.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-001736 AC-5：手工线程池工具 {@code ContextExecutors.decorate}——与全局
 * ContextTaskDecorator 等价透传（五类上下文 + 任务结束清理）；无上下文场景行为不变。
 * 纯 JUnit（无 Spring）。
 *
 * <p>口径说明（并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 API 形态假设：静态 {@code ContextExecutors.decorate(Executor)} 返回
 *       {@code Executor}（AC-5 原文「用 ContextExecutors.decorate 提交」）；若交付为
 *       decorate(ExecutorService)/ExecutorService 返回型等变体，用例机械同步。</li>
 * </ul>
 */
class REQ20261125001736ContextExecutorsTest {

    private final ExecutorService rawWorker = Executors.newSingleThreadExecutor();

    private final Executor decorated = ContextExecutors.decorate(rawWorker);

    @AfterEach
    void cleanup() {
        rawWorker.shutdownNow();
        ContextSnapshot.clear();
    }

    // ---------- AC-5：ContextExecutors.decorate 提交 → 等价透传 ----------

    @Test
    void should_propagateFiveContexts_when_submittedViaDecoratedExecutor() throws Exception {
        UserContext.bind(new UserContext("user-exec-001736", Set.of("ops")));
        TenantContext.bind(new TenantContext("t-exec-001736"));
        TraceContext.bind(new TraceContext("trace-exec-001736", "span-exec-001736"));
        LocaleContext.bind(new LocaleContext(Locale.forLanguageTag("en-US")));
        ClientContext.bind(new ClientContext(Channel.B, "device-exec-001736", "app-exec-001736"));

        AtomicReference<String[]> seen = new AtomicReference<>();
        decorated.execute(() -> seen.set(new String[] {
                UserContext.get() == null ? null : UserContext.get().userId(),
                TenantContext.get() == null ? null : TenantContext.get().tenantId(),
                TraceContext.get() == null ? null : TraceContext.get().traceId(),
                LocaleContext.get() == null ? null : LocaleContext.get().locale().toLanguageTag(),
                ClientContext.get() == null ? null : ClientContext.get().deviceId()
        }));

        awaitSeen(seen);

        assertThat(seen.get()).as("ContextExecutors.decorate 与 ContextTaskDecorator 等价透传")
                .containsExactly("user-exec-001736", "t-exec-001736", "trace-exec-001736", "en-US", "device-exec-001736");
    }

    // ---------- AC-5：任务结束清理 ----------

    @Test
    void should_clearContextsOnWorkerThread_when_taskCompletes() throws Exception {
        UserContext.bind(new UserContext("user-exec-001736", Set.of()));
        TenantContext.bind(new TenantContext("t-exec-001736"));
        TraceContext.bind(new TraceContext("trace-exec-001736", "span-exec-001736"));
        LocaleContext.bind(new LocaleContext(Locale.CHINA));
        ClientContext.bind(new ClientContext(Channel.B, "device-exec-001736", "app-exec-001736"));

        decorated.execute(() -> { });
        decorated.execute(() -> { });

        // 清理探针须经未装饰 raw 池提交观察：装饰语义在提交时刻捕获提交线程上下文，
        // 经装饰提交会回放主线程上下文而观察不到池线程残留（ContextExecutorsTest 同口径）
        AtomicReference<String[]> leftover = new AtomicReference<>();
        rawWorker.submit(() -> leftover.set(new String[] {
                UserContext.get() == null ? null : UserContext.get().userId(),
                TenantContext.get() == null ? null : TenantContext.get().tenantId(),
                TraceContext.get() == null ? null : TraceContext.get().traceId(),
                LocaleContext.get() == null ? null : LocaleContext.get().locale().toLanguageTag(),
                ClientContext.get() == null ? null : ClientContext.get().deviceId()
        })).get(5, TimeUnit.SECONDS);

        assertThat(leftover.get()).as("任务结束后 worker 线程五类上下文清理")
                .containsOnlyNulls();
        // 提交线程不受影响
        assertThat(UserContext.get().userId()).isEqualTo("user-exec-001736");
    }

    // ---------- AC-5：无上下文场景行为不变 ----------

    @Test
    void should_runWithoutContext_when_nothingBound() throws Exception {
        ContextSnapshot.clear();
        AtomicReference<Boolean> clean = new AtomicReference<>();
        decorated.execute(() -> clean.set(
                UserContext.get() == null && TenantContext.get() == null && TraceContext.get() == null
                        && LocaleContext.get() == null && ClientContext.get() == null));

        awaitTrue(() -> clean.get() != null);
        assertThat(clean.get()).as("无上下文提交：任务正常执行").isTrue();
    }

    // ---------- 支撑 ----------

    private static void awaitSeen(AtomicReference<String[]> ref) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (ref.get() == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("等待 worker 任务完成超时");
            }
            Thread.sleep(20);
        }
    }

    private static void awaitTrue(BooleanSupplier supplier) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!supplier.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("等待条件满足超时");
            }
            Thread.sleep(20);
        }
    }
}
