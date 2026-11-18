package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.infrastructure.redis.CaptchaRedis;
import com.acme.platform.auth.internal.sms.SmsChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * C 端验证码用例（consumer-auth-refresh-dataperm）：发送频控（60s/手机号）+
 * 通道发送（开发态日志通道，生产必须覆盖，14.3.3）。
 * 编排不开启事务（无 DB 写；Redis 与通道发送事务外，M-05 精神）。
 */
@Service
public class CaptchaUseCase {

    private static final Logger log = LoggerFactory.getLogger(CaptchaUseCase.class);

    private final CaptchaRedis captchaRedis;
    private final SmsChannel smsChannel;

    public CaptchaUseCase(CaptchaRedis captchaRedis, SmsChannel smsChannel) {
        this.captchaRedis = captchaRedis;
        this.smsChannel = smsChannel;
    }

    /** 发送验证码（频控命中 → CAPTCHA_SEND_LIMITED；存储失败 fail-closed 不发送）。 */
    public void send(String phone) {
        String code = captchaRedis.generate(phone);
        if (code == null) {
            throw new BizException(AuthError.CAPTCHA_SEND_LIMITED);
        }
        try {
            smsChannel.sendCaptcha(phone, code);
        } catch (RuntimeException ex) {
            log.error("验证码通道发送失败：phone={}", phone, ex);
            throw new BizException(AuthError.CAPTCHA_SEND_LIMITED);
        }
    }
}
