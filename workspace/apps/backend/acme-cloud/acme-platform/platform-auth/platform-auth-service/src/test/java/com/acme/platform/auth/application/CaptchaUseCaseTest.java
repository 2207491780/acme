package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.infrastructure.redis.CaptchaRedis;
import com.acme.platform.auth.internal.sms.SmsChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码发送用例测试（consumer-auth-refresh-dataperm）：频控命中 429、通道失败 429、正常发送经通道。
 */
class CaptchaUseCaseTest {

    private final CaptchaRedis captchaRedis = mock(CaptchaRedis.class);
    private final SmsChannel smsChannel = mock(SmsChannel.class);
    private final CaptchaUseCase useCase = new CaptchaUseCase(captchaRedis, smsChannel);

    @Test
    void sendLimitedWhenThrottled() {
        when(captchaRedis.generate("13800138000")).thenReturn(null);
        assertThatThrownBy(() -> useCase.send("13800138000"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_SEND_LIMITED.code()));
        verify(smsChannel, org.mockito.Mockito.never()).sendCaptcha(anyString(), anyString());
    }

    @Test
    void channelFailureSurfacesAsSendLimited() {
        when(captchaRedis.generate("13800138000")).thenReturn("123456");
        doThrow(new IllegalStateException("sms down")).when(smsChannel).sendCaptcha(anyString(), anyString());
        assertThatThrownBy(() -> useCase.send("13800138000"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_SEND_LIMITED.code()));
    }

    @Test
    void sendsCodeViaChannel() {
        when(captchaRedis.generate("13800138000")).thenReturn("123456");
        assertThatCode(() -> useCase.send("13800138000")).doesNotThrowAnyException();
        verify(smsChannel).sendCaptcha(eq("13800138000"), eq("123456"));
    }
}
