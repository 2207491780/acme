package com.acme.platform.system.application;

import com.acme.common.serialization.Json;
import com.acme.framework.web.support.WebError;
import com.acme.common.core.BizException;
import com.acme.platform.system.domain.SysPushToken;
import com.acme.platform.system.domain.SysUserPreference;
import com.acme.platform.system.infrastructure.repository.SysPushTokenRepository;
import com.acme.platform.system.infrastructure.repository.SysUserPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 个人中心偏好与推送令牌用例（REQ-20261119-082531 确认记录⑤ + REQ-20261121-200415 确认记录②③④，
 * 认证即可无权限点）：
 * <ul>
 *   <li>偏好=本人单行按域 upsert（prefs jsonb 载荷 {@code {"messagePush": boolean,
 *       "themePreference": {...}}}，域缺省=不动该域）；messagePush 未设行 GET 回默认 false；
 *       themePreference 未设行 GET 回 null（后端不内置主题默认值，前端以 core DEFAULT_PREFERENCES
 *       兜底——防双源漂移）；PUT 两域全空 → 400。</li>
 *   <li>themePreference 结构校验 fail-closed（{@link ThemePreferenceValidator}）：非法 → 400 既有
 *       偏好校验错误码（REQ-082531 终稿口径，不新增错误码段），校验先于任何写入，原数据不动。</li>
 *   <li>推送令牌=按 token 全局唯一 upsert（同一设备重装/换账号注册归一新主体行）与本人物理删
 *       （幂等 10.3.1，软删行占唯一键冲突故走 forceDeleteByQuery 旁路）。</li>
 * </ul>
 * prefs JSON 读写经共用 {@link Json} 门面（2.5.2）；读失败 fail-open 回默认（展示面不阻断）。
 */
@Service
public class UserPreferenceUseCase {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceUseCase.class);

    /**
     * prefs jsonb 载荷形态（确认记录②⑤）：messagePush=站内信新消息 APP 推送开关；
     * themePreference=主题偏好 blob（按域 upsert 读改写，另一域原样保留）。
     */
    public record Prefs(Boolean messagePush, Object themePreference) {
    }

    /** 未设偏好行的缺省视图（messagePush=false，REQ-082531 确认记录⑤；themePreference=null，200415 ④）。 */
    static final boolean DEFAULT_MESSAGE_PUSH = false;

    /** extensions 单键值序列化字节上限（16KB，确认记录③）。 */
    static final int EXTENSION_VALUE_LIMIT_BYTES = 16 * 1024;

    /** extensions 总序列化字节上限（64KB，确认记录③）。 */
    static final int EXTENSIONS_TOTAL_LIMIT_BYTES = 64 * 1024;

    private final SysUserPreferenceRepository preferenceRepository;
    private final SysPushTokenRepository pushTokenRepository;
    private final Json json;

    public UserPreferenceUseCase(SysUserPreferenceRepository preferenceRepository,
                                 SysPushTokenRepository pushTokenRepository, Json json) {
        this.preferenceRepository = preferenceRepository;
        this.pushTokenRepository = pushTokenRepository;
        this.json = json;
    }

    /** 本人通知偏好（未设行/载荷异常回默认 false——展示面 fail-open）。 */
    public boolean messagePushOf(Long userId) {
        Prefs prefs = prefsOf(userId);
        return prefs != null && Boolean.TRUE.equals(prefs.messagePush());
    }

    /** 本人主题偏好 blob（未设行/载荷异常回 null——展示面 fail-open，确认记录④不内置默认值）。 */
    public Object themePreferenceOf(Long userId) {
        Prefs prefs = prefsOf(userId);
        return prefs == null ? null : prefs.themePreference();
    }

    /**
     * 本人偏好按域 upsert（本人单行，user_id 唯一键；幂等重复 PUT 归一）。
     * 域语义（确认记录②）：域=null=不动该域（保留既有值）；至少一域非 null 否则 400；
     * themePreference 非法（结构校验不过）→ 400 且原数据不动（校验先于读取写入，fail-closed）。
     */
    @Transactional
    public void upsertPreferences(Long userId, Boolean messagePush, Object themePreference) {
        if (messagePush == null && themePreference == null) {
            throw new BizException(WebError.VALIDATION_FAILED, "messagePush 与 themePreference 至少一项非空");
        }
        if (themePreference != null) {
            ThemePreferenceValidator.validate(themePreference);
            validateExtensionsSize(themePreference);
        }
        Prefs existing = prefsOf(userId);
        Boolean effectiveMessagePush = messagePush != null ? messagePush
                : (existing != null && existing.messagePush() != null ? existing.messagePush() : DEFAULT_MESSAGE_PUSH);
        Object effectiveThemePreference = themePreference != null
                ? themePreference
                : (existing != null ? existing.themePreference() : null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messagePush", effectiveMessagePush);
        if (effectiveThemePreference != null) {
            payload.put("themePreference", effectiveThemePreference);
        }
        SysUserPreference row = preferenceRepository.findByUserId(userId).orElse(null);
        if (row == null) {
            preferenceRepository.save(new SysUserPreference(userId, json.toJson(payload)));
        } else {
            row.setPrefs(json.toJson(payload));
            preferenceRepository.save(row);
        }
    }

    /**
     * extensions 限额校验（确认记录③：单键 ≤16KB、总 ≤64KB，json 序列化 UTF-8 字节计量；
     * 内容透传不深检——结构合规已由 {@link ThemePreferenceValidator} 承载）。
     */
    private void validateExtensionsSize(Object themePreference) {
        if (!(themePreference instanceof Map<?, ?> root)
                || !(root.get("extensions") instanceof Map<?, ?> extensions)) {
            return;
        }
        for (Map.Entry<?, ?> entry : extensions.entrySet()) {
            int valueBytes = json.toJson(entry.getValue()).getBytes(StandardCharsets.UTF_8).length;
            if (valueBytes > EXTENSION_VALUE_LIMIT_BYTES) {
                throw new BizException(WebError.VALIDATION_FAILED,
                        "主题偏好非法: extensions 单键超 16KB: " + entry.getKey());
            }
        }
        int totalBytes = json.toJson(extensions).getBytes(StandardCharsets.UTF_8).length;
        if (totalBytes > EXTENSIONS_TOTAL_LIMIT_BYTES) {
            throw new BizException(WebError.VALIDATION_FAILED, "主题偏好非法: extensions 总量超 64KB");
        }
    }

    /** 本人偏好载荷解析（未设行回 null；载荷异常 fail-open 回 null 并 WARN，不阻断展示面）。 */
    private Prefs prefsOf(Long userId) {
        Optional<SysUserPreference> row = preferenceRepository.findByUserId(userId);
        if (row.isEmpty()) {
            return null;
        }
        try {
            return json.fromJson(row.get().getPrefs(), Prefs.class);
        } catch (RuntimeException ex) {
            log.warn("偏好载荷解析失败（回默认值）: userId={}", userId, ex);
            return null;
        }
    }

    /**
     * 推送令牌注册（upsert 按 token 唯一键）：既有行归属他人 → 归一新主体（设备换绑语义，
     * 旧主体不再推送）；platform 同步刷新。
     */
    @Transactional
    public void registerPushToken(Long userId, String token, String platform) {
        SysPushToken existing = pushTokenRepository.findByToken(token).orElse(null);
        if (existing == null) {
            pushTokenRepository.save(new SysPushToken(userId, token, platform));
        } else {
            existing.setUserId(userId);
            existing.setPlatform(platform);
            pushTokenRepository.save(existing);
        }
    }

    /** 推送令牌移除（仅本人行；幂等——不存在视为已删）。 */
    @Transactional
    public void removePushToken(Long userId, String token) {
        pushTokenRepository.deleteByTokenAndUserId(token, userId);
    }
}
