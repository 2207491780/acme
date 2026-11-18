package com.acme.framework.lock.support;

import com.acme.common.contract.ErrorCodeLayout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码段位锁定（DP-008 段位表：SYS-07=framework-lock）。
 */
class LockErrorLayoutTest {

    @Test
    void allCodesRoundTripWithinSegment07() {
        for (LockError error : LockError.values()) {
            ErrorCodeLayout layout = ErrorCodeLayout.parse(error.code());
            assertThat(layout.serviceId()).isEqualTo("SYS");
            assertThat(layout.moduleSegment()).isEqualTo(7);
            assertThat(layout.code()).isEqualTo(error.code());
        }
    }
}
