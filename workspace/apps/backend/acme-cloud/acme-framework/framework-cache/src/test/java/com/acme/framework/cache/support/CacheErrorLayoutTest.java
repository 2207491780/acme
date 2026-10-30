package com.acme.framework.cache.support;

import com.acme.common.contract.ErrorCodeLayout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码段位锁定（DP-008 段位表：SYS-06=framework-cache）。
 */
class CacheErrorLayoutTest {

    @Test
    void allCodesRoundTripWithinSegment06() {
        for (CacheError error : CacheError.values()) {
            ErrorCodeLayout layout = ErrorCodeLayout.parse(error.code());
            assertThat(layout.serviceId()).isEqualTo("SYS");
            assertThat(layout.moduleSegment()).isEqualTo(6);
            assertThat(layout.code()).isEqualTo(error.code());
        }
    }
}
