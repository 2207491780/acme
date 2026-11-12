package com.acme.platform.auth.application;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminCaptchaResponse;
import com.acme.platform.auth.infrastructure.redis.ImageCaptchaRedis;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * B 端图形验证码用例（login-guard-captcha）：Hutool LineCaptcha（主流实现，4 位字符 + 干扰线）
 * 生成 PNG data-URI，captchaId → 字符码经 Redis 一次性消费（防在线识别爆破）。
 * 编排不开启事务（纯 Redis 与内存图片，无 DB 写，M-05 精神）。
 */
@Service
public class ImageCaptchaUseCase {

    private final ImageCaptchaRedis imageCaptchaRedis;

    public ImageCaptchaUseCase(ImageCaptchaRedis imageCaptchaRedis) {
        this.imageCaptchaRedis = imageCaptchaRedis;
    }

    /** 生成图形验证码（captchaId 一次性下发；图片为 data:image/png;base64 data-URI，前端直接渲染）。 */
    public AdminCaptchaResponse generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(130, 40, 4, 12);
        String captchaId = UUID.randomUUID().toString();
        imageCaptchaRedis.store(captchaId, captcha.getCode());
        return new AdminCaptchaResponse(captchaId, captcha.getImageBase64Data());
    }

    /**
     * 登录前置校验（一次性消费语义；错误区分：值在但码错 → INVALID，值缺失 → EXPIRED，
     * 与短信验证码消费端登录用例同型口径）。
     */
    public void verify(String captchaId, String code) {
        if (!imageCaptchaRedis.verify(captchaId, code)) {
            throw new BizException(imageCaptchaRedis.exists(captchaId)
                    ? AuthError.CAPTCHA_INVALID : AuthError.CAPTCHA_EXPIRED);
        }
    }
}
