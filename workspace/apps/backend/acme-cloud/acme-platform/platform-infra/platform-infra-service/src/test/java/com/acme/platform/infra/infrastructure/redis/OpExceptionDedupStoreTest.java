package com.acme.platform.infra.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 异常日志去重键存储单元测试（REQ-20261117-061017 确认①；纯 JUnit + Mockito）：
 * SETNX 认领/重复语义、键形（service:type:pathHash）、Redis 不可达 fail-open。
 * 端到端去重窗口由 PG+Redis 集成测试覆盖。
 */
class OpExceptionDedupStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private OpExceptionDedupStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new OpExceptionDedupStore(redisTemplate);
    }

    /** 首次认领（SETNX true）→ 应落库。 */
    @Test
    void should_claim_when_keyAbsent() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(store.tryAcquire("platform-demo", "java.lang.IllegalStateException", "/demo/a")).isTrue();
    }

    /** 窗口内重复（SETNX false）→ 跳过。 */
    @Test
    void should_reportDuplicate_when_keyExists() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(store.tryAcquire("platform-demo", "java.lang.IllegalStateException", "/demo/a")).isFalse();
    }

    /** Redis 不可达（模板抛出）→ fail-open 放行（宁可多记不漏记）。 */
    @Test
    void should_failOpen_when_redisUnavailable() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThat(store.tryAcquire("platform-demo", "java.lang.IllegalStateException", "/demo/a")).isTrue();
    }

    /** 键形：infra:op-exception:dedup:{service}:{type}:{pathHash}——同 path 同键、异 path 异键。 */
    @Test
    void should_stableKeyShape_when_samePathAndType() {
        String keyA = OpExceptionDedupStore.keyOf("platform-demo", "java.lang.IllegalStateException", "/demo/a");
        String keyAgain = OpExceptionDedupStore.keyOf("platform-demo", "java.lang.IllegalStateException", "/demo/a");
        String keyOtherPath = OpExceptionDedupStore.keyOf("platform-demo", "java.lang.IllegalStateException", "/demo/b");
        String keyOtherType = OpExceptionDedupStore.keyOf("platform-demo", "java.lang.NullPointerException", "/demo/a");

        assertThat(keyA).startsWith("infra:op-exception:dedup:platform-demo:java.lang.IllegalStateException:");
        assertThat(keyA).isEqualTo(keyAgain);
        assertThat(keyA).isNotEqualTo(keyOtherPath);
        assertThat(keyA).isNotEqualTo(keyOtherType);
        assertThat(keyA).as("路径以 SHA-256 摘要收敛（64 hex）")
                .matches(".*:[0-9a-f]{64}");
    }

    /** null path：以空串参与摘要，不抛。 */
    @Test
    void should_handleNullPath_when_contextMissing() {
        String key = OpExceptionDedupStore.keyOf("platform-demo", "java.lang.IllegalStateException", null);
        assertThat(key).startsWith("infra:op-exception:dedup:");
    }
}
