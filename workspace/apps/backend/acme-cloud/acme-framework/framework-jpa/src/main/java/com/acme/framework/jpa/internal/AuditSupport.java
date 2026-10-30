package com.acme.framework.jpa.internal;

import com.acme.common.util.IdGenerator;
import com.acme.common.util.SnowflakeIdGenerator;
import com.acme.framework.core.support.ApplicationContexts;
import com.acme.framework.jpa.config.JpaProperties;
import com.acme.framework.jpa.support.Auditor;
import com.acme.framework.jpa.support.JpaAuditDefaults;

/**
 * 审计填充支撑（FR-008、12.3.1）：雪花 ID 与操作人解析。
 * 操作人缺失一律回退 {@link JpaAuditDefaults#SYSTEM_OPERATOR}（6.2：缺失不得导致持久化失败）；
 * 雪花生成器优先容器 IdGenerator bean，缺省生成器 worker 必填（12.3.1：禁静默 0/0 回退防多实例碰撞）。
 */
public final class AuditSupport {

    /** 缺省雪花生成器（按 JpaProperties.Id 懒建一次；null=尚未建）。 */
    private static volatile IdGenerator defaultIds;

    private AuditSupport() {
    }

    /** 雪花主键（12.3.1 禁自增）；优先容器 IdGenerator bean。 */
    public static long nextId() {
        try {
            return ApplicationContexts.getBean(IdGenerator.class).nextId();
        } catch (RuntimeException ex) {
            return defaultIdGenerator().nextId();
        }
    }

    /**
     * 当前操作人（FR-008）；永不返回 null（6.2 缺省口径 {@link JpaAuditDefaults#SYSTEM_OPERATOR}），
     * 容器异常/未就绪同样回退，保证 {@code created_by/updated_by} NOT NULL 列不因审计不可用而插入失败。
     */
    public static String operator() {
        try {
            Auditor auditor = ApplicationContexts.getBean(Auditor.class);
            String operator = auditor.currentOperator();
            return operator == null || operator.isBlank() ? JpaAuditDefaults.SYSTEM_OPERATOR : operator;
        } catch (RuntimeException ex) {
            return JpaAuditDefaults.SYSTEM_OPERATOR;
        }
    }

    private static IdGenerator defaultIdGenerator() {
        IdGenerator generator = defaultIds;
        if (generator != null) {
            return generator;
        }
        synchronized (AuditSupport.class) {
            if (defaultIds == null) {
                defaultIds = buildDefaultIds();
            }
            return defaultIds;
        }
    }

    private static IdGenerator buildDefaultIds() {
        Integer workerId;
        long datacenterId;
        try {
            JpaProperties.Id config = ApplicationContexts.getBean(JpaProperties.class).getId();
            workerId = config.getWorkerId();
            datacenterId = config.getDatacenterId();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("未注册 IdGenerator bean 且无法读取 acme.jpa.id.* 配置（12.3.1）", ex);
        }
        if (workerId == null) {
            throw new IllegalStateException(
                    "未注册 IdGenerator bean 且未配置 acme.jpa.id.worker-id（12.3.1：禁静默 0/0 回退防主键碰撞）");
        }
        return new SnowflakeIdGenerator(workerId, datacenterId);
    }
}
