package com.acme.framework.tx.config;

import com.acme.framework.tx.internal.SeataAssemblyValidator;
import com.acme.framework.tx.support.RollbackPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-024/AC-021/AC-024、BR-003/004）。
 */
class TxSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TxAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.tx.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TxProperties.class);
                    assertThat(context).doesNotHaveBean(SeataAssemblyValidator.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaultsAndGlobalOff() {
        runner.run(context -> {
            TxProperties properties = context.getBean(TxProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isGlobalEnabled()).isFalse();
            assertThat(properties.getTxGroup()).isEqualTo("acme-tx-group");
            assertThat(properties.getRollbackPolicy()).isEqualTo(RollbackPolicy.ROLLBACK_ON_FAILURE);
            assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(60));
            // 默认不开启：无分布式事务行为（AC-021）
            assertThat(context).doesNotHaveBean(SeataAssemblyValidator.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.tx.tx-group=g1",
                        "acme.tx.rollback-policy=ROLLBACK_ON_UNCHECKED",
                        "acme.tx.timeout=30s")
                .run(context -> {
                    TxProperties properties = context.getBean(TxProperties.class);
                    assertThat(properties.getTxGroup()).isEqualTo("g1");
                    assertThat(properties.getRollbackPolicy()).isEqualTo(RollbackPolicy.ROLLBACK_ON_UNCHECKED);
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void globalSwitchWiresValidatorOnlyWhenExplicitlyEnabled() {
        // 显式开启但 Seata 基础设施缺失：启动期显式失败（SYS-19-0002，不得静默降级，架构设计 13.3）
        runner.withPropertyValues("acme.tx.global.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOfSatisfying(com.acme.common.core.BizException.class,
                                    ex -> assertThat(ex.errorCode())
                                            .isEqualTo(com.acme.framework.tx.support.TxError.SEATA_MISSING));
                });
    }
}
