package com.acme.common.test;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 测试基类（FR-030；DP-014 定稿）：服务侧测试可复用的公共断言支撑。
 * 提供业务异常断言（错误码级）；可继承亦可静态调用（支撑面随集成测试场景以兼容方式扩展）。
 * 测试栈仅限测试域（BR-023、Q-012）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public abstract class BaseTest {

    /**
     * 断言可执行块抛出携带指定错误码的 {@link BizException}（FR-030；DP-014 定稿）。
     *
     * @return 被抛出的异常（供调用方追加断言）
     */
    protected static BizException assertBizException(Executable executable, ErrorCode expectedErrorCode) {
        BizException thrown = assertThrows(BizException.class, executable);
        assertEquals(expectedErrorCode.code(), thrown.errorCode().code(),
                "期望错误码 " + expectedErrorCode.code() + "，实际 " + thrown.errorCode().code());
        return thrown;
    }
}
