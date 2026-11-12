package com.acme.framework.tenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-tenant 配置属性（FR-022、Backend.md 8.1.3）：前缀 {@code acme.tenant}，全字段默认值（架构设计 12）。
 */
@ConfigurationProperties("acme.tenant")
public class TenantProperties {

    /** 模块开关（FR-030，默认 true）。 */
    private boolean enabled = true;

    /**
     * 无租户 fail-closed（platform-services-bootstrap 修订口径）：会话创建期返回未绑定哨兵
     * （读恒空集），写入经 UnboundTenantGuard 显式拒绝（TENANT_REQUIRED）——拒绝下沉数据操作面，
     * 语义保留（原 NULL 策略可致过滤退化，仍移除）。
     */
    private final Audit audit = new Audit();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Audit getAudit() {
        return audit;
    }

    /** 跨租户审计（FR-004；仅控制日志产生，关闭须知悉合规风险——架构设计 12）。 */
    public static class Audit {

        /** 审计开关（默认 true）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
