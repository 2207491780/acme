package com.acme.framework.flex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-mybatis-flex 配置属性（FR-026、Backend.md 8.1.3）：前缀 {@code acme.flex}，全字段默认值（架构设计 10）。
 */
@ConfigurationProperties("acme.flex")
public class FlexProperties {

    /** 模块开关（FR-026，默认 true）。 */
    private boolean enabled = true;

    private final Audit audit = new Audit();

    private final SoftDelete softDelete = new SoftDelete();

    private final OptimisticLock optimisticLock = new OptimisticLock();

    private final SlowSql slowSql = new SlowSql();

    private final Id id = new Id();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Audit getAudit() {
        return audit;
    }

    public SoftDelete getSoftDelete() {
        return softDelete;
    }

    public OptimisticLock getOptimisticLock() {
        return optimisticLock;
    }

    public SlowSql getSlowSql() {
        return slowSql;
    }

    public Id getId() {
        return id;
    }

    /**
     * 雪花 ID 配置（12.3.1）。
     * worker-id 无默认值，缺失即显式失败——多实例静默共用 0/0 会主键碰撞。
     */
    public static class Id {

        /** worker 标识（0-31；必填，缺失=启动/首用失败，禁静默 0/0 回退）。 */
        private Integer workerId;

        /** 数据中心标识（0-31，默认 0）。 */
        private long datacenterId = 0;

        public Integer getWorkerId() {
            return workerId;
        }

        public void setWorkerId(Integer workerId) {
            this.workerId = workerId;
        }

        public long getDatacenterId() {
            return datacenterId;
        }

        public void setDatacenterId(long datacenterId) {
            this.datacenterId = datacenterId;
        }
    }

    /** 审计字段填充配置（FR-008）。 */
    public static class Audit {

        /** 审计填充开关（FR-008，默认 true；关闭后行为与未引入一致）。 */
        private boolean enabled = true;

        /** 操作人缺省值（6.2，默认 system）。 */
        private String systemOperator = "system";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSystemOperator() {
            return systemOperator;
        }

        public void setSystemOperator(String systemOperator) {
            this.systemOperator = systemOperator;
        }
    }

    /** 软删除配置（FR-009）。 */
    public static class SoftDelete {

        /** 软删除开关（FR-009，默认 true；关闭后删除走物理删除）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 乐观锁配置（FR-010）。版本字段映射（{@code @Column(version = true)}）为 AuditBase 能力面
     * （12.2，FR-010 交付物）；本开关登记冲突语义承诺（默认 true，见实现说明「数据模型落地」）。
     */
    public static class OptimisticLock {

        /** 乐观锁开关（FR-010，默认 true）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 慢 SQL 日志配置（FR-013）。 */
    public static class SlowSql {

        /** 慢 SQL 日志开关（FR-013，默认 true）。 */
        private boolean enabled = true;

        /** 慢 SQL 阈值（毫秒，默认 1000）。 */
        private long thresholdMs = 1000;

        /** 是否输出绑定参数（脱敏后，默认 true）。 */
        private boolean logParameters = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getThresholdMs() {
            return thresholdMs;
        }

        public void setThresholdMs(long thresholdMs) {
            this.thresholdMs = thresholdMs;
        }

        public boolean isLogParameters() {
            return logParameters;
        }

        public void setLogParameters(boolean logParameters) {
            this.logParameters = logParameters;
        }
    }
}
