package com.acme.framework.jpa.config;

import com.acme.framework.jpa.support.Auditor;
import com.acme.framework.jpa.support.BatchWriter;
import com.acme.framework.jpa.support.EntityQueries;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-026、AC-020、BR-017）。
 */
class JpaSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.jpa.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EntityQueries.class);
                    assertThat(context).doesNotHaveBean(BatchWriter.class);
                    assertThat(context).doesNotHaveBean(Auditor.class);
                    assertThat(context).doesNotHaveBean(JpaProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        JpaProperties properties = new JpaProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAudit().isEnabled()).isTrue();
        assertThat(properties.getAudit().getSystemOperator()).isEqualTo("system");
        assertThat(properties.getSoftDelete().isEnabled()).isTrue();
        assertThat(properties.getOptimisticLock().isEnabled()).isTrue();
        assertThat(properties.getBatch().getChunkSize()).isEqualTo(50);
        assertThat(properties.getSlowSql().isEnabled()).isTrue();
        assertThat(properties.getSlowSql().getThresholdMs()).isEqualTo(1000);
        assertThat(properties.getSlowSql().isLogParameters()).isTrue();
    }

    @Test
    void auditDisabledSkipsAuditor() {
        runner.withPropertyValues("acme.jpa.audit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(Auditor.class));
    }
}
