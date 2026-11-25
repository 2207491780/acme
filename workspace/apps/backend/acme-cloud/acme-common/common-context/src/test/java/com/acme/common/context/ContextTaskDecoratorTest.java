package com.acme.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文透传任务装饰器测试（FR-012；REQ-20261125-001736 AC-5）：五类上下文透传、任务结束清理、
 * 异常路径清理与无上下文场景行为不变。
 */
class ContextTaskDecoratorTest {

    private final ContextTaskDecorator decorator = new ContextTaskDecorator();

    @AfterEach
    void cleanup() {
        ContextSnapshot.clear();
    }

    @Test
    void should_propagateAllFiveContexts_when_taskSubmittedWithBinding() throws Exception {
        bindAll();

        Runnable wrapped = decorator.decorate(() -> {
            assertThat(UserContext.get()).extracting(UserContext::userId).isEqualTo("u-1");
            assertThat(TenantContext.get()).extracting(TenantContext::tenantId).isEqualTo("t-1");
            assertThat(TraceContext.get()).extracting(TraceContext::traceId).isEqualTo("tr-1");
            assertThat(LocaleContext.get()).extracting(LocaleContext::locale).isEqualTo(Locale.forLanguageTag("zh-CN"));
            assertThat(ClientContext.get()).extracting(ClientContext::deviceId).isEqualTo("dev-1");
        });
        // 装饰在提交线程执行，任务在池线程执行：池线程内五类上下文可用
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(wrapped).get();
        } finally {
            ContextSnapshot.clear();
        }
    }

    @Test
    void should_clearContextsAfterRun_when_taskCompletes() throws Exception {
        bindAll();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(decorator.decorate(() -> {
            })).get();
            // 任务结束清理：池线程不残留上下文（跨任务泄漏面收口）
            pool.submit(() -> assertThat(UserContext.get()).isNull()).get();
            pool.submit(() -> assertThat(TenantContext.get()).isNull()).get();
            pool.submit(() -> assertThat(TraceContext.get()).isNull()).get();
            pool.submit(() -> assertThat(LocaleContext.get()).isNull()).get();
            pool.submit(() -> assertThat(ClientContext.get()).isNull()).get();
        } finally {
            ContextSnapshot.clear();
        }
    }

    @Test
    void should_clearContexts_when_taskThrows() throws Exception {
        bindAll();
        Runnable wrapped = decorator.decorate(() -> {
            throw new IllegalStateException("boom");
        });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> future = pool.submit(wrapped);
            assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalStateException.class);
            // 异常路径同样清理（finally 保证）
            pool.submit(() -> {
                assertThat(UserContext.get()).isNull();
                assertThat(TenantContext.get()).isNull();
            }).get();
        } finally {
            ContextSnapshot.clear();
        }
    }

    @Test
    void should_behaveUnchanged_when_noContextBound() {
        // 无上下文场景行为不变：装饰后任务照常执行（快照恒空，恢复/清理等价空操作）
        Runnable wrapped = decorator.decorate(() -> assertThat(UserContext.get()).isNull());
        wrapped.run();
    }

    @Test
    void should_rejectNullTask() {
        assertThatThrownBy(() -> decorator.decorate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void bindAll() {
        UserContext.bind(new UserContext("u-1", Set.of("admin")));
        TenantContext.bind(new TenantContext("t-1"));
        TraceContext.bind(new TraceContext("tr-1", "span-1"));
        LocaleContext.bind(new LocaleContext(Locale.forLanguageTag("zh-CN")));
        ClientContext.bind(new ClientContext(Channel.B, "dev-1", null));
    }
}
