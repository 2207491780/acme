package com.acme.framework.tx.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 约束面与错误码段位锁定测试（FR-022/023、DP-008 段位表、16.1）。
 */
class TxSupportTest {

    @Test
    void errorCodesLockedToSys19Segment() {
        assertThat(TxError.TX_CONFIG_INVALID.code()).isEqualTo("SYS-19-0001");
        assertThat(TxError.SEATA_MISSING.code()).isEqualTo("SYS-19-0002");
    }

    @Test
    void timeoutBudgetRequiresPositiveTxTimeout() {
        assertThatThrownBy(() -> TxConstraints.requireTimeoutBudget(Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void txTimeoutMustBeLessThanUpstreamReadTimeout() {
        assertThatThrownBy(() -> TxConstraints.requireTimeoutBudget(Duration.ofSeconds(60), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> TxConstraints.requireTimeoutBudget(Duration.ofSeconds(30), Duration.ofSeconds(60)))
                .doesNotThrowAnyException();
    }
}
