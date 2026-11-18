package com.acme.platform.system.api;

/**
 * 租户验证码策略视图（REQ-20261117-011514）：B 端登录图形验证码开关的当前生效值。
 * 无配置行租户缺省 false（迁移内置默认关闭）；C 端短信验证码路径不涉及本策略。
 */
public record CaptchaPolicyView(boolean captchaEnabled) {
}
