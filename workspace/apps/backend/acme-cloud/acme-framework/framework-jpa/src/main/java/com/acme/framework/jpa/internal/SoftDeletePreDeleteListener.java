package com.acme.framework.jpa.internal;

import com.acme.framework.jpa.support.AuditBase;
import com.acme.framework.jpa.support.SoftDelete;
import org.hibernate.HibernateException;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 软删除改写（FR-009、6.3 适配层）：{@link SoftDelete} 实体删除=置删除标记（否决物理删除改写 UPDATE），
 * 同步维护 updated_at/updated_by/version（FR-008 审计链与乐观锁不因软删断裂）；
 * 重复删除幂等（10.3.1：已删再删按成功处理）；开关 {@code acme.jpa.soft-delete.enabled=false} 时走物理删除。
 * 注意：bulk JPQL/原生 delete 不触发 PRE_DELETE 事件，{@code @SoftDelete} 实体禁用 bulk 删除（FR-009 边界）。
 */
public class SoftDeletePreDeleteListener implements PreDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger(SoftDeletePreDeleteListener.class);

    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        Object entity = event.getEntity();
        if (entity == null || !SoftDeleteMarker.test(entity.getClass())) {
            return false;
        }
        if (!(entity instanceof AuditBase base)) {
            log.warn("@SoftDelete 实体未继承 AuditBase，跳过软删除改写（FR-009）：{}", entity.getClass().getName());
            return false;
        }
        if (Boolean.TRUE.equals(base.getDeleted())) {
            // 重复删除幂等（10.3.1）：已删记录再删按成功处理，否决物理删除
            return true;
        }
        markDeleted(event);
        return true;
    }

    private void markDeleted(PreDeleteEvent event) {
        try {
            String table = event.getPersister().getTableName();
            Object id = event.getId();
            String operator = AuditSupport.operator();
            java.time.Instant now = java.time.Instant.now();
            // doWork 走原生 JDBC 不触发 flush，避免删除处理中再入 flush 递归（6.3 适配点核验）
            event.getSession().doWork(connection -> {
                try (var statement = connection.prepareStatement(
                        "update " + table + " set deleted = true, updated_at = ?, updated_by = ?,"
                                + " version = version + 1 where id = ? and deleted = false")) {
                    statement.setObject(1, java.sql.Timestamp.from(now));
                    statement.setString(2, operator);
                    statement.setObject(3, id);
                    statement.executeUpdate();
                }
            });
        } catch (RuntimeException ex) {
            throw new HibernateException("软删除标记写入失败（FR-009）", ex);
        }
    }

    /** 标记判定与开关读取收敛（internal）；注解判定按类缓存，删除热路径零重复反射。 */
    static final class SoftDeleteMarker {

        private static final ClassValue<Boolean> SOFT_DELETE_ANNOTATED = new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                return type.getAnnotation(SoftDelete.class) != null;
            }
        };

        private SoftDeleteMarker() {
        }

        /** 实体是否标注 {@link SoftDelete} 且开关开启（配置读取经 AuditSupport 同源容器访问）。 */
        static boolean test(Class<?> entityType) {
            return SOFT_DELETE_ANNOTATED.get(entityType) && SoftDeleteToggles.enabled();
        }
    }
}
