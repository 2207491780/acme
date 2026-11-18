package com.acme.framework.tenant.interceptor;

import com.acme.framework.tenant.config.TenantProperties;
import com.acme.framework.tenant.support.CrossTenantAuditLogger;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.framework.tenant.support.TenantBypassScope;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 忽略注解切面（FR-003/004、AC-003/004：开旁路→执行→审计→关旁路；异常路径同样关闭）。
 */
class IgnoreTenantAspectTest {

    static class Probe {

        @IgnoreTenant("平台管理#查询")
        String crossTenantRead() {
            assertThat(TenantBypassScope.isActive()).as("执行期旁路生效（AC-003）").isTrue();
            return "all-rows";
        }

        @IgnoreTenant
        void failing() {
            throw new IllegalStateException("boom");
        }
    }

    static class RecordingAuditLogger extends CrossTenantAuditLogger {

        final AtomicBoolean recorded = new AtomicBoolean();

        @Override
        public void record(String accessPoint) {
            recorded.set(true);
        }
    }

    private final RecordingAuditLogger auditLogger = new RecordingAuditLogger();

    private Probe proxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Probe());
        factory.addAspect(new IgnoreTenantAspect(auditLogger, new TenantProperties()));
        return factory.getProxy();
    }

    @Test
    void bypassesExecutesAuditsAndCloses() {
        Probe probe = proxy();
        assertThat(probe.crossTenantRead()).isEqualTo("all-rows");
        assertThat(auditLogger.recorded).as("跨租户访问产生审计（AC-004）").isTrue();
        assertThat(TenantBypassScope.isActive()).isFalse();
    }

    @Test
    void exceptionPathClosesBypassAndStillAudits() {
        Probe probe = proxy();
        assertThatThrownBy(probe::failing).isInstanceOf(IllegalStateException.class);
        assertThat(TenantBypassScope.isActive()).as("异常路径同样清理旁路").isFalse();
        assertThat(auditLogger.recorded).as("失败访问同样入审计（FR-004 每次旁路访问）").isTrue();
    }

    @Test
    void auditDisabledSkipsRecord() {
        TenantProperties properties = new TenantProperties();
        properties.getAudit().setEnabled(false);
        AspectJProxyFactory factory = new AspectJProxyFactory(new Probe());
        factory.addAspect(new IgnoreTenantAspect(auditLogger, properties));
        Probe probe = factory.getProxy();
        probe.crossTenantRead();
        assertThat(auditLogger.recorded).isFalse();
    }
}
