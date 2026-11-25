package com.acme.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-001736 AC-5：Spring 全局 {@code ContextTaskDecorator}——@Async/@Scheduled
 * 等线程池经装饰器提交后，子线程五类上下文（USER/TENANT/TRACE/LOCALE/CLIENT）可用且
 * 任务结束清理（复用 ContextSnapshot capture/restore）；提交线程上下文不受影响；
 * 无上下文场景行为不变。纯 JUnit（无 Spring），按 TaskDecorator 契约手工驱动装饰器。
 *
 * <p>口径说明（并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 构造假设：ContextTaskDecorator 无参构造（钉死事实未列依赖）。</li>
 *   <li>AMB-2 decorate(Runnable) 返回包装任务（Spring TaskDecorator 契约口径：
 *       decorate 在提交线程执行 capture，包装任务在 worker 线程 restore + 任务后清理）。</li>
 * </ul>
 */
class REQ20261125001736ContextTaskDecoratorTest {

    private final ContextTaskDecorator decorator = new ContextTaskDecorator();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @AfterEach
    void cleanup() {
        worker.shutdownNow();
        ContextSnapshot.clear();
    }

    // ---------- AC-5：子线程五类上下文可用 ----------

    @Test
    void should_propagateFiveContexts_when_taskRunsOnWorkerThread() throws Exception {
        UserContext.bind(new UserContext("user-001736", Set.of("admin")));
        TenantContext.bind(new TenantContext("t-001736"));
        TraceContext.bind(new TraceContext("trace-001736", "span-001736"));
        LocaleContext.bind(new LocaleContext(Locale.forLanguageTag("zh-CN")));
        ClientContext.bind(new ClientContext(Channel.C, "device-001736", "app-001736"));

        AtomicReference<String[]> seen = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> seen.set(new String[] {
                UserContext.get() == null ? null : UserContext.get().userId(),
                TenantContext.get() == null ? null : TenantContext.get().tenantId(),
                TraceContext.get() == null ? null : TraceContext.get().traceId(),
                LocaleContext.get() == null ? null : LocaleContext.get().locale().toLanguageTag(),
                ClientContext.get() == null ? null : ClientContext.get().deviceId()
        }));

        worker.submit(decorated).get(5, TimeUnit.SECONDS);

        assertThat(seen.get()).as("子线程五类上下文（USER/TENANT/TRACE/LOCALE/CLIENT）可用")
                .containsExactly("user-001736", "t-001736", "trace-001736", "zh-CN", "device-001736");
        // 提交线程上下文不受装饰影响
        assertThat(UserContext.get().userId()).isEqualTo("user-001736");
        assertThat(TenantContext.get().tenantId()).isEqualTo("t-001736");
    }

    // ---------- AC-5：任务结束清理（同一 worker 线程后续任务视角） ----------

    @Test
    void should_clearAllContextsOnWorkerThread_when_taskCompletes() throws Exception {
        UserContext.bind(new UserContext("user-001736", Set.of()));
        TenantContext.bind(new TenantContext("t-001736"));
        TraceContext.bind(new TraceContext("trace-001736", "span-001736"));
        LocaleContext.bind(new LocaleContext(Locale.CHINA));
        ClientContext.bind(new ClientContext(Channel.C, "device-001736", "app-001736"));

        worker.submit(decorator.decorate(() -> { })).get(5, TimeUnit.SECONDS);

        // 同一 worker 线程的后续任务：上一任务结束后五类上下文应已清理（不残留）
        AtomicReference<String[]> leftover = new AtomicReference<>();
        // 清理探针须经未装饰提交观察：装饰语义在提交时刻捕获提交线程上下文，
        // 经装饰提交会回放主线程上下文而观察不到池线程残留（ContextExecutorsTest 同口径）
        worker.submit(() -> leftover.set(new String[] {
                UserContext.get() == null ? null : UserContext.get().userId(),
                TenantContext.get() == null ? null : TenantContext.get().tenantId(),
                TraceContext.get() == null ? null : TraceContext.get().traceId(),
                LocaleContext.get() == null ? null : LocaleContext.get().locale().toLanguageTag(),
                ClientContext.get() == null ? null : ClientContext.get().deviceId()
        })).get(5, TimeUnit.SECONDS);

        assertThat(leftover.get()).as("任务结束后 worker 线程五类上下文清理")
                .containsExactly(null, null, null, null, null);
        // 提交线程上下文不受任务结束清理影响（装饰器只清 worker 线程）
        assertThat(UserContext.get().userId()).isEqualTo("user-001736");
    }

    // ---------- AC-5：无上下文场景行为不变 ----------

    @Test
    void should_runWithoutContext_when_nothingBound() throws Exception {
        ContextSnapshot.clear();
        AtomicReference<Boolean> clean = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> clean.set(
                UserContext.get() == null && TenantContext.get() == null && TraceContext.get() == null
                        && LocaleContext.get() == null && ClientContext.get() == null));

        worker.submit(decorated).get(5, TimeUnit.SECONDS);

        assertThat(clean.get()).as("无上下文提交：任务正常执行且不注入残留").isTrue();
    }
}
