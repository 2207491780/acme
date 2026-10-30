package com.acme.common.test;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BaseTest 公共断言支撑测试（FR-030；DP-014 定稿）：业务异常断言按错误码判定。
 */
class BaseTestSupportTest extends BaseTest {

    enum TestError implements ErrorCode {
        DEMO("T-00-0001", "演示错误"),
        OTHER("T-00-0002", "另一错误");

        private final String code;
        private final String defaultMessage;

        TestError(String code, String defaultMessage) {
            this.code = code;
            this.defaultMessage = defaultMessage;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String defaultMessage() {
            return defaultMessage;
        }
    }

    @Test
    void assertBizExceptionMatchesErrorCode() {
        BizException thrown = assertBizException(
                () -> {
                    throw new BizException(TestError.DEMO, "自定义提示");
                }, TestError.DEMO);
        assertSame(TestError.DEMO, thrown.errorCode());
    }

    @Test
    void assertBizExceptionRejectsOtherErrorCode() {
        assertThrows(AssertionError.class, () -> assertBizException(
                () -> {
                    throw new BizException(TestError.OTHER);
                }, TestError.DEMO));
        assertThrows(AssertionError.class, () -> assertBizException(
                () -> {
                    throw new IllegalStateException("非业务异常");
                }, TestError.DEMO));
    }
}
