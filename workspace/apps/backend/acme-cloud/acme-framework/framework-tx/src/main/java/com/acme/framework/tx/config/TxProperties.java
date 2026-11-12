package com.acme.framework.tx.config;

import com.acme.framework.tx.support.RollbackPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 分布式事务装配配置（FR-021/022、8.1.3 全字段默认值；架构设计 15 章）。
 * 两级开关：{@code enabled}（模块开关，Modules.md 3.5）+ {@code global.enabled}（默认 false，
 * 显式开启才接线 Seata TM/RM，8.2.1 禁止默认开启）。
 */
@ConfigurationProperties(prefix = "acme.tx")
public class TxProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 分布式事务全局开关组（{@code acme.tx.global.enabled}，8.2.1）。 */
    private final Global global = new Global();

    /** 事务分组（FR-021）。 */
    private String txGroup = "acme-tx-group";

    /** 失败回滚策略（FR-022）。 */
    private RollbackPolicy rollbackPolicy = RollbackPolicy.ROLLBACK_ON_FAILURE;

    /** 事务超时预算（FR-022；应小于上游读超时，10.4）。 */
    private Duration timeout = Duration.ofSeconds(60);

    /** 全局开关组（默认 false，仅显式开启生效，8.2.1、FR-021）。 */
    public static class Global {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Global getGlobal() {
        return global;
    }

    /** 便捷读取全局开关（{@code acme.tx.global.enabled}）。 */
    public boolean isGlobalEnabled() {
        return global.isEnabled();
    }

    public String getTxGroup() {
        return txGroup;
    }

    public void setTxGroup(String txGroup) {
        this.txGroup = txGroup;
    }

    public RollbackPolicy getRollbackPolicy() {
        return rollbackPolicy;
    }

    public void setRollbackPolicy(RollbackPolicy rollbackPolicy) {
        this.rollbackPolicy = rollbackPolicy;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
