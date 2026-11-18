package com.acme.framework.tx.internal;

import com.acme.common.core.BizException;
import com.acme.framework.tx.config.TxProperties;
import com.acme.framework.tx.support.TxError;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seata 装配校验测试（FR-021/022、AC-021/022、架构设计 13.3：显式失败不静默降级）。
 */
class SeataAssemblyValidatorTest {

    private static TxProperties enabled() {
        TxProperties properties = new TxProperties();
        properties.getGlobal().setEnabled(true);
        return properties;
    }

    @Test
    void globalOffSkipsValidation() {
        TxProperties properties = new TxProperties();
        SeataAssemblyValidator validator = new SeataAssemblyValidator(properties, "java.lang.Object");
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void validAssemblyPasses() {
        SeataAssemblyValidator validator = new SeataAssemblyValidator(enabled(), "java.lang.Object");
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void blankTxGroupFailsExplicitly() {
        TxProperties properties = enabled();
        properties.setTxGroup(" ");
        SeataAssemblyValidator validator = new SeataAssemblyValidator(properties, "java.lang.Object");
        assertThatThrownBy(validator::validate)
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TxError.TX_CONFIG_INVALID));
    }

    @Test
    void nonPositiveTimeoutFailsExplicitly() {
        TxProperties properties = enabled();
        properties.setTimeout(Duration.ZERO);
        SeataAssemblyValidator validator = new SeataAssemblyValidator(properties, "java.lang.Object");
        assertThatThrownBy(validator::validate)
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TxError.TX_CONFIG_INVALID));
    }

    @Test
    void missingSeataInfrastructureFailsExplicitly() {
        SeataAssemblyValidator validator =
                new SeataAssemblyValidator(enabled(), "io.seata.NotOnClasspath");
        assertThatThrownBy(validator::validate)
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TxError.SEATA_MISSING));
    }
}
