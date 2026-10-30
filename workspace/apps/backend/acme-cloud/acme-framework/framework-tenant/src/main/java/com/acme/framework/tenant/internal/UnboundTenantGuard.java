package com.acme.framework.tenant.internal;

import com.acme.common.core.BizException;
import com.acme.framework.tenant.support.TenantError;
import com.acme.framework.tenant.support.TenantIdentifierResolver;
import org.hibernate.annotations.TenantId;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;

import java.lang.reflect.Field;

/**
 * 未绑定租户写入拒绝（缺租户 fail-closed 的数据操作面落点）：会话创建期返回
 * {@link TenantIdentifierResolver#UNBOUND_TENANT} 哨兵后，租户归属实体（含 @TenantId 字段）的读被过滤为空集
 * （不跨租户出数）；本守卫对**租户归属实体**在未绑定会话中的 INSERT 显式拒绝（TENANT_REQUIRED，
 * 不产生哨兵租户脏行）——会话级判定（{@code session.getTenantIdentifier()}）不依赖 @TenantId 绑定进
 * 状态数组的时序；平台级目录实体（无 @TenantId，如权限点 / 菜单 / 租户目录）的无租户维护属合法场景，
 * 不拦。internal 包，模块外不得引用（Modules.md 2.3）。
 */
public final class UnboundTenantGuard implements PreInsertEventListener {

    /** 实体类是否携带 @TenantId 字段（含父类；ClassValue 缓存热路径零反射扫描）。 */
    private static final ClassValue<Boolean> TENANT_ENTITY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                for (Field field : cursor.getDeclaredFields()) {
                    if (field.isAnnotationPresent(TenantId.class)) {
                        return Boolean.TRUE;
                    }
                }
            }
            return Boolean.FALSE;
        }
    };

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        Object entity = event.getEntity();
        if (entity == null || !TENANT_ENTITY.get(entity.getClass())) {
            return false;
        }
        String sessionTenant = event.getSession().getTenantIdentifier();
        if (TenantIdentifierResolver.UNBOUND_TENANT.equals(sessionTenant)) {
            throw new BizException(TenantError.TENANT_REQUIRED);
        }
        return false;
    }
}
