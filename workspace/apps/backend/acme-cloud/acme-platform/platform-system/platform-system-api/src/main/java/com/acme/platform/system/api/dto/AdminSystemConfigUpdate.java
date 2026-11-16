package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 租户级系统配置修改请求（REQ-20261116-230354；upsert 语义，version=乐观锁版本）。
 * lockIdleTimeoutMs 取值集 {0,60000,300000,900000} 在用例层校验（业务失败码≠0）；
 * captchaEnabled 为 boolean 开关（REQ-20261117-011514），无取值集校验。
 * REQ-20261117-025200 增补页脚两字段：footerCopyright 可空（≤255），footerVisible 必填开关
 * （确认记录⑤：footerVisible=false 时文案不渲染，仍随 upsert 保存）。
 *
 * @param watermarkEnabled  水印开关
 * @param captchaEnabled    登录图形验证码开关
 * @param lockIdleTimeoutMs 闲置锁屏时长（毫秒）
 * @param footerCopyright   页脚版权文案（可空；≤255）
 * @param footerVisible     页脚是否显示
 * @param version           乐观锁版本（wire 字符串化）
 */
public record AdminSystemConfigUpdate(@NotNull Boolean watermarkEnabled,
                                      @NotNull Boolean captchaEnabled,
                                      @NotNull Integer lockIdleTimeoutMs,
                                      @Size(max = 255) String footerCopyright,
                                      @NotNull Boolean footerVisible,
                                      @NotNull Long version) {
}
