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
 * REQ-20261125-001736 AC-4/R4：缓存租户键生成器（framework-cache TenantKeyGenerator，
 * bean 名 tenantKeyGenerator）——绑定租户 → 键前缀 {@code t:{tenantId}:}；旁路 → {@code t:*:}；
 * 未绑定且无旁路 → 抛业务码 TENANT_REQUIRED（fail-closed）。纯 JUnit（无 Spring），
 * KeyGenerator 契约面 generate(target, method, args) 直驱。
 *
 * <p>口径说明（并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 构造假设：TenantKeyGenerator 无参构造（已随交付验证：交付切片同构造）。</li>
 *   <li>AMB-2 旁路缝（已随交付对齐）：common-context TenantBypassSignal.push()/pop()
 *       （交付切片 TenantKeyGeneratorCacheSliceTest 同缝；无需 framework-tenant 依赖）。</li>
 *   <li>AMB-3 键后缀未钉死：仅断言前缀段（t:{tenantId}: / t:*:）与同参稳定性；后缀派生规则由实现自定。</li>
 *   <li>AMB-4 错误码断言用字符串 TENANT_REQUIRED，不 import framework-tenant TenantError
 *       （framework-cache 当前无该依赖，避免测试引入实现侧依赖假设）。</li>
 * </ul>
 *
 * <p>交付裁决同步（worker 机械同步）：错误码值遵循 ErrorCodeLayout 三段式——
 * framework-cache 自有段位 SYS-06-0003（常量名 TENANT_REQUIRED），断言改用
 * {@code CacheError.TENANT_REQUIRED.code()}（AMB-4 的字面量假设销项）。</p>
 */
class REQ20261125001736TenantKeyGeneratorTest {

    private final TenantKeyGenerator generator = new TenantKeyGenerator();

    /** 键生成探针接口：Method 反射取用。 */
    interface ProbeOp {
        String load(String key);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        while (TenantBypassSignal.isActive()) {
            TenantBypassSignal.pop();
        }
    }

    // ---------- AC-4：绑定租户 → t:{tenantId}: 前缀 ----------

    @Test
    void should_prefixTenantSegment_when_tenantBound() throws Exception {
        Method method = ProbeOp.class.getMethod("load", String.class);
        TenantContext.bind(new TenantContext("t-001736"));
        try {
            Object first = generator.generate(new Object(), method, "p1");
            Object second = generator.generate(new Object(), method, "p1");
            assertThat(String.valueOf(first)).as("绑定租户 → 键前缀 t:{tenantId}:").startsWith("t:t-001736:");
            assertThat(first).as("同租户同参两次生成同键（二次命中前提）").isEqualTo(second);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void should_differAcrossTenants_when_differentTenantBound() throws Exception {
        Method method = ProbeOp.class.getMethod("load", String.class);
        TenantContext.bind(new TenantContext("t-001736"));
        Object first = generator.generate(new Object(), method, "p1");
        TenantContext.bind(new TenantContext("t-999"));
        Object second = generator.generate(new Object(), method, "p1");
        assertThat(String.valueOf(second)).startsWith("t:t-999:");
        assertThat(second).as("不同租户键不同（缓存条目隔离）").isNotEqualTo(first);
    }

    // ---------- AC-4：旁路 → t:*: 前缀 ----------

    @Test
    void should_prefixWildcardSegment_when_bypassActive() throws Exception {
        Method method = ProbeOp.class.getMethod("load", String.class);
        TenantBypassSignal.push();
        try {
            Object key = generator.generate(new Object(), method, "p1");
            assertThat(String.valueOf(key)).as("旁路 → 键前缀 t:*:（与单租户条目不混用）").startsWith("t:*:");
        } finally {
            TenantBypassSignal.pop();
        }
    }

    // ---------- AC-4：未绑定且无旁路 → TENANT_REQUIRED（fail-closed） ----------

    @Test
    void should_throwTenantRequired_when_unboundAndNoBypass() throws Exception {
        Method method = ProbeOp.class.getMethod("load", String.class);
        TenantContext.clear();
        assertThatThrownBy(() -> generator.generate(new Object(), method, "p1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .as("fail-closed：业务码 TENANT_REQUIRED（SYS-06 段，ErrorCodeLayout 三段式）")
                        .isEqualTo(CacheError.TENANT_REQUIRED.code()));
    }
}
