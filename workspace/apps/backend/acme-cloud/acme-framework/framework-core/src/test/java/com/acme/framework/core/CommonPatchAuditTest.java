package com.acme.framework.core;

import com.acme.common.contract.ErrorCodeLayout;
import com.acme.common.core.CommonError;
import com.acme.common.core.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-013 common 修补合规核对：本期修补 = common-core 仅新增 {@link CommonError}（BR-016、Backend.md 7.2.1），
 * 已登记于实现说明「改动清单」并经 G3a 单独评审通过；错误码字面量按 ErrorCodeLayout（Q-010）规范宽度。
 */
class CommonPatchAuditTest {

    @Test
    void commonPatchIsAdditionsOnlyEnumCarryingDesignatedCodes() {
        assertThat(CommonError.class.isEnum()).isTrue();
        assertThat(ErrorCode.class.isAssignableFrom(CommonError.class)).isTrue();
        assertThat(CommonError.NOT_FOUND.code()).isEqualTo("SYS-00-0002");
        assertThat(CommonError.INTERNAL_ERROR.code()).isEqualTo("SYS-00-0006");
        assertThat(CommonError.values()).hasSize(2);
    }

    @Test
    void codesRoundTripThroughErrorCodeLayout() {
        for (CommonError error : CommonError.values()) {
            assertThat(ErrorCodeLayout.parse(error.code()).code())
                    .as("错误码 %s 须与 ErrorCodeLayout 往返一致（Q-010）", error.code())
                    .isEqualTo(error.code());
        }
    }
}
