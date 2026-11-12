package com.acme.framework.tenant.internal;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.tenant.support.TenantBypassScope;
import com.acme.framework.tenant.support.TenantError;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.InsertListener;

import java.lang.reflect.Field;

/**
 * 未绑定租户写入拒绝（缺租户 fail-closed 的数据操作面落点）：缺租户上下文时
 * {@link FlexTenantFactory} 返回 {@link FlexTenantFactory#UNBOUND_TENANT} 哨兵后，租户归属实体
 * （含租户列）的读被过滤为空集（不跨租户出数）；本守卫对**租户归属实体**在未绑定上下文中的 INSERT
 * 显式拒绝（TENANT_REQUIRED，不产生哨兵租户脏行）——值注入由 MyBatis-Flex 内建（TenantFactory 覆盖），
 * 守卫只做拒绝判定。平台级目录实体（无租户列，如权限点 / 菜单 / 租户目录）的无租户维护属合法场景，
 * 不拦。internal 包，模块外不得引用（Modules.md 2.3）。
 */
public final class TenantWriteGuard implements InsertListener {

    /** 实体类租户列字段（@Column(tenantId=true)；含父类；ClassValue 缓存热路径零反射扫描）。 */
    private static final ClassValue<Boolean> TENANT_ENTITY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                for (Field field : cursor.getDeclaredFields()) {
                    Column column = field.getAnnotation(Column.class);
                    if (column != null && column.tenantId()) {
                        return Boolean.TRUE;
                    }
                }
            }
            return Boolean.FALSE;
        }
    };

    @Override
    public void onInsert(Object entity) {
        if (entity == null || !TENANT_ENTITY.get(entity.getClass())) {
            return;
        }
        if (TenantBypassScope.isActive()) {
            // 旁路：全租户维护合法（12.4.2 显式声明场景），不拒绝
            return;
        }
        TenantContext context = TenantContext.get();
        String tenantId = context == null ? null : context.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(TenantError.TENANT_REQUIRED);
        }
    }
}
