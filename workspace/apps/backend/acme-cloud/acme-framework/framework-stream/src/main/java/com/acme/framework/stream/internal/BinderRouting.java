package com.acme.framework.stream.internal;

import com.acme.common.core.BizException;
import com.acme.framework.stream.support.StreamError;
import org.springframework.core.env.Environment;

/**
 * 多 binder 路由校验（FR-016/AC-017，POC-3 口径）：按绑定配置路由，目标 binder 不可用显式失败、不误投。
 */
public class BinderRouting {

    private final Environment environment;

    public BinderRouting(Environment environment) {
        this.environment = environment;
    }

    /**
     * 发送前校验（FR-016 异常分支）：已配置绑定的 binder 必须可解析；未配置绑定放行（StreamBridge 缺省路由）。
     */
    public void validate(String binding) {
        if (binding == null || binding.isBlank()) {
            throw new BizException(StreamError.SEND_FAILED, "目标绑定不能为空");
        }
        String prefix = "spring.cloud.stream.bindings." + binding;
        String binder = environment.getProperty(prefix + ".binder");
        if (binder == null || binder.isBlank()) {
            // 未显式路由：随缺省 binder（StreamBridge 缺省语义）
            return;
        }
        String binderType = environment.getProperty("spring.cloud.stream.binders." + binder + ".type");
        if (binderType == null || binderType.isBlank()) {
            throw new BizException(StreamError.SEND_FAILED, "目标 binder 未配置: " + binder);
        }
    }
}
