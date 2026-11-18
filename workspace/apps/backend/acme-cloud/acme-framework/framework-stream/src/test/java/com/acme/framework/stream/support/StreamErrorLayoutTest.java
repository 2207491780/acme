package com.acme.framework.stream.support;

import com.acme.common.contract.ErrorCodeLayout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码段位锁定（DP-008 段位表：SYS-08=framework-stream）。
 */
class StreamErrorLayoutTest {

    @Test
    void allCodesRoundTripWithinSegment08() {
        for (StreamError error : StreamError.values()) {
            ErrorCodeLayout layout = ErrorCodeLayout.parse(error.code());
            assertThat(layout.serviceId()).isEqualTo("SYS");
            assertThat(layout.moduleSegment()).isEqualTo(8);
            assertThat(layout.code()).isEqualTo(error.code());
        }
    }
}
