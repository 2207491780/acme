package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminCaptchaResponse;
import com.acme.platform.auth.infrastructure.redis.ImageCaptchaRedis;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图形验证码用例测试（login-guard-captcha）：生成 data-URI 与存储、一次性消费通过、
 * 错误区分（值在 → INVALID / 值缺失 → EXPIRED，与短信验证码消费端口径同型）。
 */
class ImageCaptchaUseCaseTest {

    private final ImageCaptchaRedis imageCaptchaRedis = mock(ImageCaptchaRedis.class);
    private final ImageCaptchaUseCase useCase = new ImageCaptchaUseCase(imageCaptchaRedis);

    @Test
    void generateStoresCodeAndReturnsDataUri() {
        AdminCaptchaResponse response = useCase.generate();
        assertThat(response.captchaId()).isNotBlank();
        assertThat(response.imageBase64Data()).startsWith("data:image/png;base64,");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(imageCaptchaRedis).store(eq(response.captchaId()), code.capture());
        assertThat(code.getValue()).hasSize(4).matches("[A-Za-z0-9]{4}");
    }

    @Test
    void verifyPassesThroughWhenRedisConfirms() {
        when(imageCaptchaRedis.verify("cid-1", "A1B2")).thenReturn(true);
        assertThatCode(() -> useCase.verify("cid-1", "A1B2")).doesNotThrowAnyException();
    }

    @Test
    void verifyWrongCodeSurfacesInvalidWhenValueStillExists() {
        when(imageCaptchaRedis.verify("cid-1", "XXXX")).thenReturn(false);
        when(imageCaptchaRedis.exists("cid-1")).thenReturn(true);
        assertThatThrownBy(() -> useCase.verify("cid-1", "XXXX"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_INVALID.code()));
    }

    @Test
    void verifyMissingValueSurfacesExpired() {
        when(imageCaptchaRedis.verify("cid-1", "XXXX")).thenReturn(false);
        when(imageCaptchaRedis.exists("cid-1")).thenReturn(false);
        assertThatThrownBy(() -> useCase.verify("cid-1", "XXXX"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_EXPIRED.code()));
    }
}
