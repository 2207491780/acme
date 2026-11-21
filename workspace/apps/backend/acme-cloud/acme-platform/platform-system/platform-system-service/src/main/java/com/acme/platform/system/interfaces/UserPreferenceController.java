package com.acme.platform.system.interfaces;

import com.acme.common.context.UserContext;
import com.acme.common.core.Result;
import com.acme.platform.system.api.dto.AdminPreferenceView;
import com.acme.platform.system.api.dto.AdminPushTokenRequest;
import com.acme.platform.system.application.UserPreferenceUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人中心偏好与推送令牌接口（REQ-20261119-082531 确认记录⑤ + REQ-20261121-200415 确认记录②④；
 * 路径与契约 §API 逐字一致；认证即可访问——无 @RequirePermission 新权限点）。
 * 本人语义（me：UserContext.userId，同 MessageController 收件人口径）。
 * 偏好两域：GET 回 messagePush（未设行 false）+ themePreference（未设行 null，确认记录④）；
 * PUT 按域 upsert（至少一域非 null 否则 400，themePreference 结构校验由用例层承载）。
 */
@RestController
@RequestMapping("/admin/v1/system/me")
public class UserPreferenceController {

    private final UserPreferenceUseCase preferenceUseCase;

    public UserPreferenceController(UserPreferenceUseCase preferenceUseCase) {
        this.preferenceUseCase = preferenceUseCase;
    }

    /** 本人偏好两域视图（未设行 messagePush=false + themePreference=null，确认记录④）。 */
    @GetMapping("/preferences")
    public AdminPreferenceView preferences() {
        Long userId = currentUserId();
        return new AdminPreferenceView(preferenceUseCase.messagePushOf(userId),
                preferenceUseCase.themePreferenceOf(userId));
    }

    /** 本人偏好按域 upsert（本人单行，幂等；至少一域非 null，themePreference 结构校验在用例层）。 */
    @PutMapping("/preferences")
    public AdminPreferenceView upsertPreferences(@Valid @RequestBody AdminPreferenceView request) {
        preferenceUseCase.upsertPreferences(currentUserId(), request.messagePush(), request.themePreference());
        return new AdminPreferenceView(
                preferenceUseCase.messagePushOf(currentUserId()),
                preferenceUseCase.themePreferenceOf(currentUserId()));
    }

    /** 推送令牌注册（upsert 按 token 唯一键）。 */
    @PostMapping("/push-tokens")
    public Result<Void> registerPushToken(@Valid @RequestBody AdminPushTokenRequest request) {
        preferenceUseCase.registerPushToken(currentUserId(), request.token(), request.platform());
        return Result.ok(null);
    }

    /** 推送令牌移除（仅本人行；幂等）。 */
    @DeleteMapping("/push-tokens")
    public Result<Void> removePushToken(@RequestParam String token) {
        preferenceUseCase.removePushToken(currentUserId(), token);
        return Result.ok(null);
    }

    /** 当前主体（String userId → Long；解析失败归 0=系统主体，无偏好无令牌 fail-closed）。 */
    private static Long currentUserId() {
        UserContext context = UserContext.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            return 0L;
        }
        try {
            return Long.valueOf(context.userId());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
