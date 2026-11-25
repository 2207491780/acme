package com.acme.framework.cache;

import com.acme.common.context.TenantBypassSignal;
import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.cache.support.CacheError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261125-001736 AC-4：Spring Cache 切面闭环——{@code @Cacheable(keyGenerator="tenantKeyGenerator")}
 * 且 TenantContext 绑定：同租户两次调用缓存键含 {@code t:{tenantId}:} 段且二次命中；
 * 未绑定且旁路未激活 → 抛 TENANT_REQUIRED；旁路激活 → 键含 {@code t:*:} 段且旁路结果
 * 不与单租户缓存混用。
 *
 * <p>装配：测试域最小上下文（@EnableCaching + ConcurrentMapCacheManager + 显式注册
 * bean 名 tenantKeyGenerator——bean 名为钉死事实），不触碰 framework-cache 二级缓存装配
 * （无 Redis 依赖）。旁路缝随交付对齐：common-context TenantBypassSignal.push()/pop()
 * （交付切片 TenantKeyGeneratorCacheSliceTest 同缝）；bean 名 tenantKeyGenerator 为钉死事实。</p>
 */
@SpringBootTest(classes = REQ20261125001736TenantKeyCacheHitTest.CacheProbeConfig.class)
class REQ20261125001736TenantKeyCacheHitTest {

    @Configuration
    @EnableCaching
    static class CacheProbeConfig {

        @Bean("tenantKeyGenerator")
        TenantKeyGenerator tenantKeyGenerator() {
            return new TenantKeyGenerator();
        }

        @Bean
        ConcurrentMapCacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    /** 缓存探针：调用计数可观测二次命中（public 非 final 方法供 CGLIB 代理；计数经方法读目标实例——代理壳字段恒 null）。 */
    static class Probe {
        final AtomicInteger calls = new AtomicInteger();

        /** 回源计数读取面（代理方法调用委托目标实例，可真实反映回源次数）。 */
        public int calls() {
            return calls.get();
        }

        /** 计数复位（经方法走代理委托目标实例；代理壳字段恒 null，字段直打会 NPE）。 */
        public void reset() {
            calls.set(0);
        }

        @Cacheable(cacheNames = "req001736-probe", keyGenerator = "tenantKeyGenerator")
        public String load(String key) {
            return "v-" + calls.incrementAndGet() + ":" + key;
        }
    }

    @Autowired
    private Probe probe;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        // 共享上下文共享同一缓存：每用例先清上下文/旁路信号与缓存条目，防跨用例串扰
        TenantContext.clear();
        while (TenantBypassSignal.isActive()) {
            TenantBypassSignal.pop();
        }
        Cache cache = cacheManager.getCache("req001736-probe");
        if (cache != null) {
            cache.clear();
        }
        probe.reset(); // 计数复位经方法委托目标实例（CGLIB 代理壳字段恒 null，字段直打 NPE）
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        while (TenantBypassSignal.isActive()) {
            TenantBypassSignal.pop();
        }
    }

    // ---------- AC-4：同租户两次调用 → 二次命中 ----------

    @Test
    void should_cacheHitWithinSameTenant_when_calledTwice() {
        TenantContext.bind(new TenantContext("t-001736"));
        probe.load("x");
        probe.load("x");
        assertThat(probe.calls()).as("同租户同参二次调用命中缓存").isEqualTo(1);
        assertThat(cacheKeys()).as("缓存键含 t:{tenantId}: 段")
                .anySatisfy(key -> assertThat(String.valueOf(key)).startsWith("t:t-001736:"));
    }

    // ---------- AC-4：旁路 → t:*: 段且不与单租户缓存混用 ----------

    @Test
    void should_isolateBypassEntry_when_bypassResultNotMixedIntoTenantCache() {
        TenantContext.bind(new TenantContext("t-001736"));
        probe.load("x");
        TenantBypassSignal.push();
        try {
            probe.load("x");
        } finally {
            TenantBypassSignal.pop();
        }
        assertThat(probe.calls()).as("旁路调用不命中单租户缓存条目").isEqualTo(2);
        assertThat(cacheKeys()).as("旁路键含 t:*: 段")
                .anySatisfy(key -> assertThat(String.valueOf(key)).startsWith("t:*:"));
        // 旁路结果不与单租户缓存混用：回到本租户仍命中原条目
        TenantContext.bind(new TenantContext("t-001736"));
        probe.load("x");
        assertThat(probe.calls()).as("租户条目未被旁路调用覆盖或污染").isEqualTo(2);
    }

    // ---------- AC-4：未绑定且旁路未激活 → TENANT_REQUIRED ----------

    @Test
    void should_throwTenantRequired_when_unboundAndNoBypass() {
        TenantContext.clear();
        assertThatThrownBy(() -> probe.load("x"))
                .as("@Cacheable 键生成阶段即 fail-closed")
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .as("业务码 TENANT_REQUIRED（SYS-06-0003，ErrorCodeLayout 三段式）")
                        .isEqualTo(CacheError.TENANT_REQUIRED.code()));
        assertThat(probe.calls()).as("拒绝时不进入方法体").isZero();
    }

    // ---------- 支撑 ----------

    private Iterable<Object> cacheKeys() {
        Cache cache = cacheManager.getCache("req001736-probe");
        assertThat(cache).isInstanceOf(ConcurrentMapCache.class);
        Map<Object, Object> nativeCache = ((ConcurrentMapCache) cache).getNativeCache();
        return nativeCache.keySet();
    }
}
