package com.acme.platform.auth.internal.sms;

/**
 * 短信通道 SPI（Modules.md 第 5 章：通道适配远期归 framework-*；当前唯一使用方为 platform-auth，
 * 按 2.3 准入判据（≥3 服务）暂居服务内，抽取时机登记实现说明）。
 */
public interface SmsChannel {

    /** 发送验证码短信；发送失败上抛（调用方决定失败响应语义）。 */
    void sendCaptcha(String phone, String code);
}
