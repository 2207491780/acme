package com.acme.framework.cache;

import com.acme.common.context.TenantBypassSignal;
import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户缓存键 Spring Cache 切片测试（REQ-20261125-001736 AC-4）：@Cacheable(keyGenerator=
 * "tenantKeyGenerator") 下同租户二次命中、键含 t:{tenantId}: 段、未绑定 fail-closed 抛
 * TENANT_REQUIRED、旁路 t:*: 前缀且不与单租户缓存混用。
 */
class TenantKeyGeneratorCacheSliceTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        while (TenantBypassSignal.isActive()) {
            TenantBypassSignal.pop();
        }
    }

    @Test
    void should_hitSameEntry_when_sameTenantCallsTwice() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            ProbeService probe = context.getBean(ProbeService.class);

            TenantContext.bind(new TenantContext("t-1"));
            assertThat(probe.load("k1")).isEqualTo("value-k1");
            assertThat(probe.load("k1")).isEqualTo("value-k1");
            // 同租户两次调用：回源一次（二次命中）——计数经方法读目标实例（CGLIB 代理壳字段恒 0）
            assertThat(probe.calls()).isEqualTo(1);

            Map<Object, Object> entries = nativeEntries(context);
            assertThat(entries.keySet().stream().map(String::valueOf))
                    .anyMatch(key -> key.startsWith("t:t-1:"));
        }
    }

    @Test
    void should_isolateEntries_when_tenantsDiffer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            ProbeService probe = context.getBean(ProbeService.class);

            TenantContext.bind(new TenantContext("t-1"));
            probe.load("k1");
            TenantContext.clear();
            TenantContext.bind(new TenantContext("t-2"));
            probe.load("k1");

            // 不同租户不共用缓存条目（键含租户段）
            assertThat(probe.calls()).isEqualTo(2);
            Map<Object, Object> entries = nativeEntries(context);
            assertThat(entries.keySet().stream().map(String::valueOf))
                    .anyMatch(key -> key.startsWith("t:t-1:"))
                    .anyMatch(key -> key.startsWith("t:t-2:"));
        }
    }

    @Test
    void should_failClosedWithTenantRequired_when_unboundAndNoBypass() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            ProbeService probe = context.getBean(ProbeService.class);
            TenantContext.clear();

            assertThatThrownBy(() -> probe.load("k1"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("缺少租户上下文");
            assertThat(probe.calls()).isZero();
        }
    }

    @Test
    void should_useWildcardPrefix_andNotMixWithTenantCache_when_bypassActive() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            ProbeService probe = context.getBean(ProbeService.class);

            // 旁路激活：独立计算并落 t:*: 键（旁路结果不与单租户缓存混用）
            TenantContext.bind(new TenantContext("t-1"));
            probe.load("k1");
            assertThat(probe.calls()).isEqualTo(1);

            TenantBypassSignal.push();
            try {
                probe.load("k1");
                assertThat(probe.calls()).isEqualTo(2);
                probe.load("k1");
                assertThat(probe.calls()).isEqualTo(2);
            } finally {
                TenantBypassSignal.pop();
            }

            // 回到单租户：与 t:*: 条目互不混用，命中 t:t-1 条目
            probe.load("k1");
            assertThat(probe.calls()).isEqualTo(2);

            Map<Object, Object> entries = nativeEntries(context);
            assertThat(entries.keySet().stream().map(String::valueOf))
                    .anyMatch(key -> key.startsWith("t:t-1:"))
                    .anyMatch(key -> key.startsWith("t:*:"));
        }
    }

    @Test
    void should_registerBeanUnderTenantKeyGeneratorName() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            assertThat(context.getBean("tenantKeyGenerator")).isInstanceOf(TenantKeyGenerator.class);
        }
    }

    private static Map<Object, Object> nativeEntries(AnnotationConfigApplicationContext context) {
        ConcurrentMapCache cache = (ConcurrentMapCache) context.getBean(CacheManager.class).getCache("probe");
        return cache.getNativeCache();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheConfig {

        @Bean
        public CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }

        @Bean
        public TenantKeyGenerator tenantKeyGenerator() {
            return new TenantKeyGenerator();
        }

        @Bean
        public ProbeService probeService() {
            return new ProbeService();
        }
    }

    /** @Cacheable 探针（回源计数；计数经方法读——CGLIB 代理壳字段非目标实例状态）。 */
    static class ProbeService {

        private int invocations;

        /** 回源计数读取面（代理方法调用委托目标实例，可真实反映回源次数）。 */
        public int calls() {
            return invocations;
        }

        @Cacheable(cacheNames = "probe", keyGenerator = "tenantKeyGenerator")
        public String load(String key) {
            invocations++;
            return "value-" + key;
        }
    }
}
