package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * APP 推送令牌注册请求（REQ-20261119-082531 确认记录⑤，POST /admin/v1/system/me/push-tokens）：
 * token=Expo 推送令牌（upsert 语义按 token 唯一键，重复注册归一本人行）；
 * platform 取值集 ios/android/web（sys_push_token.platform varchar(8)，确认记录⑤）。
 */
public record AdminPushTokenRequest(@NotBlank @Size(max = 512) String token,
                                    @NotBlank @Pattern(regexp = "ios|android|web")
                                    @Size(max = 8) String platform) {
}
