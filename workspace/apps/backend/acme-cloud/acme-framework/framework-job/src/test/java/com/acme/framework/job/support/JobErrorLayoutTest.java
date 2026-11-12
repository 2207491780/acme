package com.acme.framework.job.support;

import com.acme.common.contract.ErrorCodeLayout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码段位锁定（DP-008 段位表：SYS-10=framework-job）。
 */
class JobErrorLayoutTest {

    @Test
    void allCodesRoundTripWithinSegment10() {
        for (JobError error : JobError.values()) {
            ErrorCodeLayout layout = ErrorCodeLayout.parse(error.code());
            assertThat(layout.serviceId()).isEqualTo("SYS");
            assertThat(layout.moduleSegment()).isEqualTo(10);
            assertThat(layout.code()).isEqualTo(error.code());
        }
    }
}
