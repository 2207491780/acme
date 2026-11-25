package com.acme.framework.cache;

import com.acme.common.context.TenantBypassSignal;
import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.cache.support.CacheError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户缓存键生成器测试（FR-008；REQ-20261125-001736 AC-4）：键形 t:{tenantId}:{类简名}:{method}:{args逗号串}、
 * 旁路 t:*: 前缀、未绑定且旁路未激活 fail-closed 抛 TENANT_REQUIRED。
 */
class TenantKeyGeneratorTest {

    private final TenantKeyGenerator generator = new TenantKeyGenerator();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        // 旁路深度测试直推状态（唯一业务写入口为 framework-tenant TenantBypassScope），对齐清理
        while (TenantBypassSignal.isActive()) {
            TenantBypassSignal.pop();
        }
    }

    @Test
    void should_composeTenantPrefixedKey_when_tenantBound() throws Exception {
        TenantContext.bind(new TenantContext("t-1"));

        Object key = generator.generate(new Probe(), loadMethod(), "a", "b");

        assertThat(key).isEqualTo("t:t-1:Probe:load:a,b");
    }

    @Test
    void should_useWildcardPrefix_when_bypassActive() throws Exception {
        TenantContext.bind(new TenantContext("t-1"));
        TenantBypassSignal.push();
        try {
            Object key = generator.generate(new Probe(), loadMethod(), "a");
            // 旁路结果不与单租户缓存混用（AC-4）
            assertThat(key).isEqualTo("t:*:Probe:load:a");
        } finally {
            TenantBypassSignal.pop();
        }
    }

    @Test
    void should_failClosedWithTenantRequired_when_unboundAndNoBypass() throws Exception {
        TenantContext.clear();

        assertThatThrownBy(() -> generator.generate(new Probe(), loadMethod(), "a"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(CacheError.TENANT_REQUIRED.code()));
    }

    @Test
    void should_failClosed_when_blankTenantAndNoBypass() throws Exception {
        TenantContext.bind(new TenantContext("   "));

        assertThatThrownBy(() -> generator.generate(new Probe(), loadMethod(), "a"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(CacheError.TENANT_REQUIRED.code()));
    }

    @Test
    void should_useNoArgsPlaceholder_when_argumentsEmpty() throws Exception {
        TenantContext.bind(new TenantContext("t-1"));

        Object key = generator.generate(new Probe(), loadMethod());

        assertThat(key).isEqualTo("t:t-1:Probe:load:-");
    }

    @Test
    void should_produceDistinctKeys_when_tenantsDiffer() throws Exception {
        TenantContext.bind(new TenantContext("t-1"));
        Object first = generator.generate(new Probe(), loadMethod(), "a");
        TenantContext.clear();
        TenantContext.bind(new TenantContext("t-2"));
        Object second = generator.generate(new Probe(), loadMethod(), "a");

        assertThat(first).isNotEqualTo(second);
    }

    private static Method loadMethod() throws NoSuchMethodException {
        return Probe.class.getDeclaredMethod("load", String.class);
    }

    /** 键形探测目标类（SimpleName 入键）。 */
    static class Probe {

        @SuppressWarnings("unused")
        public void load(String key) {
            // 仅反射取 Method 用
        }
    }
}
