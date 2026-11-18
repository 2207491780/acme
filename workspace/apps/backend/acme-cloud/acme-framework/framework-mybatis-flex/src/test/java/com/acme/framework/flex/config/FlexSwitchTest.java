package com.acme.framework.flex.config;

import com.acme.framework.flex.support.Auditor;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-026、AC-020、BR-017）。
 */
class FlexSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlexAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.flex.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(Auditor.class);
                    assertThat(context).doesNotHaveBean(FlexProperties.class);
                    assertThat(context).doesNotHaveBean(MyBatisFlexCustomizer.class);
                    assertThat(context).doesNotHaveBean(com.mybatisflex.core.logicdelete.LogicDeleteProcessor.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        FlexProperties properties = new FlexProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAudit().isEnabled()).isTrue();
        assertThat(properties.getAudit().getSystemOperator()).isEqualTo("system");
        assertThat(properties.getSoftDelete().isEnabled()).isTrue();
        assertThat(properties.getOptimisticLock().isEnabled()).isTrue();
        assertThat(properties.getSlowSql().isEnabled()).isTrue();
        assertThat(properties.getSlowSql().getThresholdMs()).isEqualTo(1000);
        assertThat(properties.getSlowSql().isLogParameters()).isTrue();
    }

    @Test
    void auditDisabledSkipsAuditor() {
        runner.withPropertyValues("acme.flex.audit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(Auditor.class));
    }
}
