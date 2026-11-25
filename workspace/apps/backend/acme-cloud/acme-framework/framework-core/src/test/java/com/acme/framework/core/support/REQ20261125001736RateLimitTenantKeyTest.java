package com.acme.framework.core.support;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-001736 AC-6/R6：限流键租户维度——RateLimitKeyInput 增加 tenantId：
 * 非空白时 B 形态键 = prefix:tenantId:B:account:api、C 形态键 = prefix:tenantId:C:device:user:api
 * （租户段紧跟服务前缀）；空白（null/空串/空白串）时与现状逐字一致（向后兼容）；
 * FB 兜底键不变。上下文绑定侧（AC-6 given TenantContext 绑定 → 键含租户段）以
 * 「绑定后按上下文装填输入再构造」的调用缝表达——网关/注解面的真实装填方式未钉死。
 *
 * <p>构造缝已随交付对齐：7 参构造 (prefix, channel, account, device, user, api, tenantId)——
 * tenantId 追加在末位（交付 RateLimitKeyBuilderTest 租户段同缝同键形）。</p>
 */
class REQ20261125001736RateLimitTenantKeyTest {

    private final DefaultRateLimitKeyBuilder keyBuilder = new DefaultRateLimitKeyBuilder();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // ---------- R6：tenantId 非空白 → B/C 形态键含租户段（紧跟服务前缀） ----------

    @Test
    void should_insertTenantSegmentInBKey_when_inputTenantIdPresent() {
        String key = keyBuilder.build(new RateLimitKeyInput(
                "p3-sample", Channel.B, "u-1", "dev-1", "u-1", "/admin/sample/items", "t-001736"));
        assertThat(key).isEqualTo("p3-sample:t-001736:B:u-1:/admin/sample/items");
    }

    @Test
    void should_insertTenantSegmentInCKey_when_inputTenantIdPresent() {
        String key = keyBuilder.build(new RateLimitKeyInput(
                "p3-sample", Channel.C, "u-1", "dev-9", "u-1", "/app/sample/items/1", "t-001736"));
        // C 形态 api 归一化照旧（/app/sample/items/1 → /app/sample/items/{id}）
        assertThat(key).isEqualTo("p3-sample:t-001736:C:dev-9:u-1:/app/sample/items/{id}");
    }

    // ---------- AC-6 given TenantContext 绑定：绑定态构造 → 键含租户段 ----------

    @Test
    void should_carryBoundTenantSegment_when_callerBindsTenantContext() {
        TenantContext.bind(new TenantContext("t-001736"));
        try {
            String key = keyBuilder.build(new RateLimitKeyInput(
                    "p3-sample", Channel.B, "u-1", "dev-1", "u-1", "/admin/sample/items",
                    TenantContext.get().tenantId()));
            assertThat(key).as("绑定租户构造 → 键在服务前缀后含租户段")
                    .isEqualTo("p3-sample:t-001736:B:u-1:/admin/sample/items");
        } finally {
            TenantContext.clear();
        }
    }

    // ---------- R6：tenantId 空白 → 与现状逐字一致（向后兼容） ----------

    @Test
    void should_keepLegacyBKeyShape_when_tenantIdBlank() {
        assertThat(keyBuilder.build(new RateLimitKeyInput(
                "p3-sample", Channel.B, "u-1", "dev-1", "u-1", "/admin/sample/items", null)))
                .isEqualTo("p3-sample:B:u-1:/admin/sample/items");
        assertThat(keyBuilder.build(new RateLimitKeyInput(
                "p3-sample", Channel.B, "u-1", "dev-1", "u-1", "/admin/sample/items", "")))
                .isEqualTo("p3-sample:B:u-1:/admin/sample/items");
        assertThat(keyBuilder.build(new RateLimitKeyInput(
                "p3-sample", Channel.B, "u-1", "dev-1", "u-1", "/admin/sample/items", "   ")))
                .isEqualTo("p3-sample:B:u-1:/admin/sample/items");
    }

    @Test
    void should_keepLegacyCKeyShape_when_tenantIdBlank() {
        assertThat(keyBuilder.build(new RateLimitKeyInput(
                "p3-sample", Channel.C, "u-1", "dev-9", "u-1", "/app/sample/items/1", null)))
                .isEqualTo("p3-sample:C:dev-9:u-1:/app/sample/items/{id}");
        // 缺维度占位符照旧（既有 missingDimensionsUsePlaceholder 口径：前缀缺省 + 租户空白）
        assertThat(keyBuilder.build(new RateLimitKeyInput(
                null, Channel.C, null, null, null, "/app/x", "   ")))
                .isEqualTo(":C:-:-:/app/x");
    }

    // ---------- R6：FB 兜底键不变 ----------

    @Test
    void should_keepFallbackKeyUnchanged_when_fallbackBuilt() {
        assertThat(keyBuilder.buildFallback(new RateLimitFallbackInput(
                "p3-sample", "10.1.2.3", "/admin/sample/test-token")))
                .isEqualTo("p3-sample:FB:10.1.2.3:/admin/sample/test-token");
        assertThat(keyBuilder.buildFallback(new RateLimitFallbackInput(
                "p3-sample", null, "/public/x")))
                .isEqualTo("p3-sample:FB:-:/public/x");
    }
}
