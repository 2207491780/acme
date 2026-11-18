package com.acme.common.serialization.internal;

import com.acme.common.serialization.DesensitizeStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 脱敏掩码规则直测（DP-010 定稿；置于 internal 包以直测包级入口）：
 * 逐策略掩码输出（固定宽度掩码段，不暴露原文长度）与非法输入显式失败。
 */
class DesensitizeMaskTest {

    /** 手机号：前 3 后 4，中间固定 4 星（Q-008 结论）。 */
    @Test
    void phoneKeepsHeadAndTail() {
        assertEquals("138****5678", DesensitizeSerializer.mask("13812345678", DesensitizeStrategy.PHONE));
        assertThrows(IllegalArgumentException.class,
                () -> DesensitizeSerializer.mask("138456", DesensitizeStrategy.PHONE));
    }

    /** 身份证号：前 6 后 4，中间固定 8 星。 */
    @Test
    void idCardKeepsHeadAndTail() {
        assertEquals("110105********1234",
                DesensitizeSerializer.mask("110105199001011234", DesensitizeStrategy.ID_CARD));
        assertThrows(IllegalArgumentException.class,
                () -> DesensitizeSerializer.mask("110105", DesensitizeStrategy.ID_CARD));
    }

    /** 邮箱：保留本地部分首字符与完整域名。 */
    @Test
    void emailKeepsFirstCharAndDomain() {
        assertEquals("a****@example.com",
                DesensitizeSerializer.mask("alice@example.com", DesensitizeStrategy.EMAIL));
        assertThrows(IllegalArgumentException.class,
                () -> DesensitizeSerializer.mask("no-at-sign", DesensitizeStrategy.EMAIL));
        assertThrows(IllegalArgumentException.class,
                () -> DesensitizeSerializer.mask("@example.com", DesensitizeStrategy.EMAIL));
    }

    /** 银行卡号：仅保留后 4 位。 */
    @Test
    void bankCardKeepsLastFour() {
        assertEquals("****5678",
                DesensitizeSerializer.mask("6222020200112235678", DesensitizeStrategy.BANK_CARD));
        assertThrows(IllegalArgumentException.class,
                () -> DesensitizeSerializer.mask("1234", DesensitizeStrategy.BANK_CARD));
    }

    /** 中文姓名：保留姓，其余逐字星号。 */
    @Test
    void chineseNameKeepsSurname() {
        assertEquals("张*", DesensitizeSerializer.mask("张三", DesensitizeStrategy.CHINESE_NAME));
        assertEquals("欧**", DesensitizeSerializer.mask("欧阳锋", DesensitizeStrategy.CHINESE_NAME));
        assertThrows(IllegalArgumentException.class,
                () -> DesensitizeSerializer.mask("张", DesensitizeStrategy.CHINESE_NAME));
    }

    /** 全掩码：固定输出，不暴露字符与长度。 */
    @Test
    void maskAllHidesEverything() {
        assertEquals("****", DesensitizeSerializer.mask("secret-value", DesensitizeStrategy.MASK_ALL));
        assertEquals("****", DesensitizeSerializer.mask("s", DesensitizeStrategy.MASK_ALL));
    }
}
