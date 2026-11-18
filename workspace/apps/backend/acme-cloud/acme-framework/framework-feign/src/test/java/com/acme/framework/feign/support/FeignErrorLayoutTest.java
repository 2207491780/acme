package com.acme.framework.feign.support;

import com.acme.common.contract.ErrorCodeLayout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码段位锁定（DP-008 段位表：SYS-09=framework-feign）。
 */
class FeignErrorLayoutTest {

    @Test
    void allCodesRoundTripWithinSegment09() {
        for (FeignError error : FeignError.values()) {
            ErrorCodeLayout layout = ErrorCodeLayout.parse(error.code());
            assertThat(layout.serviceId()).isEqualTo("SYS");
            assertThat(layout.moduleSegment()).isEqualTo(9);
            assertThat(layout.code()).isEqualTo(error.code());
        }
    }
}
