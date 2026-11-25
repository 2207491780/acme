package com.acme.framework.cache;

import com.acme.common.context.TenantBypassSignal;
import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.cache.support.CacheError;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/**
 * 租户隔离缓存键生成器（FR-008；REQ-20261125-001736 AC-4）：键形
 * {@code t:{tenantId}:{targetClass简名}:{method}:{args逗号串}}——租户数据缓存键单源隔离。
 *
 * <p>fail-closed（AC-4）：未绑定租户且旁路未激活时抛业务码 {@link CacheError#TENANT_REQUIRED}
 * （不产生无租户键的全局共享缓存条目）；旁路激活时键取 {@code t:*:} 前缀（旁路结果不与单租户
 * 缓存混用）。旁路深度读 {@link TenantBypassSignal}（common-context 共享单源，唯一写入口为
 * framework-tenant {@code TenantBypassScope}）。</p>
 *
 * <p>消费方式：{@code @Cacheable(cacheNames="…", keyGenerator="tenantKeyGenerator")}
 * （bean 由 CacheAutoConfiguration 注册，{@code @ConditionalOnMissingBean} 可覆盖）。</p>
 */
public class TenantKeyGenerator implements KeyGenerator {

    /** 旁路激活时的租户段取值（旁路结果不与单租户缓存混用，AC-4）。 */
    public static final String BYPASS_SEGMENT = "*";

    /** 空参数段占位（键段永不缺失，与限流键 safe 占位口径一致）。 */
    private static final String NO_ARGS_SEGMENT = "-";

    @Override
    public Object generate(Object target, Method method, Object... args) {
        String tenantId = TenantBypassSignal.isActive() ? BYPASS_SEGMENT : boundTenantId();
        if (tenantId == null) {
            // 未绑定且旁路未激活：fail-closed，不产生无租户键（AC-4）
            throw new BizException(CacheError.TENANT_REQUIRED);
        }
        return "t:" + tenantId + ":" + target.getClass().getSimpleName() + ":"
                + method.getName() + ":" + composeArgs(args);
    }

    /** 当前绑定租户（TenantContext 数据域租户唯一事实源；未绑定/空白返回 null）。 */
    private static String boundTenantId() {
        TenantContext tenant = TenantContext.get();
        String tenantId = tenant == null ? null : tenant.tenantId();
        return tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    /** 参数逗号串（AC-4 键形末段；null/空参数段记 {@code -}，元素 null 记 {@code null}）。 */
    private static String composeArgs(Object... args) {
        if (args == null || args.length == 0) {
            return NO_ARGS_SEGMENT;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(args[i]);
        }
        return builder.isEmpty() ? NO_ARGS_SEGMENT : builder.toString();
    }
}
