package com.acme.platform.system.application;

import com.acme.common.serialization.Json;
import com.acme.platform.system.domain.SysPushToken;
import com.acme.platform.system.domain.SysUserPreference;
import com.acme.platform.system.infrastructure.repository.SysPushTokenRepository;
import com.acme.platform.system.infrastructure.repository.SysUserPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 个人中心偏好与推送令牌用例（REQ-20261119-082531 确认记录⑤，认证即可无权限点）：
 * 偏好=本人单行 upsert（prefs jsonb 载荷 {@code {"messagePush": boolean}}，未设行 GET 回默认 false）；
 * 推送令牌=按 token 全局唯一 upsert（同一设备重装/换账号注册归一新主体行）与本人物理删
 * （幂等 10.3.1，软删行占唯一键冲突故走 forceDeleteByQuery 旁路）。
 * prefs JSON 读写经共用 {@link Json} 门面（2.5.2）；读失败 fail-open 回默认（展示面不阻断）。
 */
@Service
public class UserPreferenceUseCase {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceUseCase.class);

    /** prefs jsonb 载荷形态（确认记录⑤）：messagePush=站内信新消息 APP 推送开关。 */
    public record Prefs(Boolean messagePush) {
    }

    /** 未设偏好行的缺省视图（messagePush=false，确认记录⑤）。 */
    static final boolean DEFAULT_MESSAGE_PUSH = false;

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
        Optional<SysUserPreference> row = preferenceRepository.findByUserId(userId);
        if (row.isEmpty()) {
            return DEFAULT_MESSAGE_PUSH;
        }
        try {
            Prefs prefs = json.fromJson(row.get().getPrefs(), Prefs.class);
            return prefs != null && Boolean.TRUE.equals(prefs.messagePush());
        } catch (RuntimeException ex) {
            log.warn("偏好载荷解析失败（回默认值）: userId={}", userId, ex);
            return DEFAULT_MESSAGE_PUSH;
        }
    }

    /** 本人偏好 upsert（本人单行，user_id 唯一键；幂等重复 PUT 归一）。 */
    @Transactional
    public void upsertMessagePush(Long userId, Boolean messagePush) {
        boolean value = Boolean.TRUE.equals(messagePush);
        SysUserPreference row = preferenceRepository.findByUserId(userId).orElse(null);
        if (row == null) {
            preferenceRepository.save(new SysUserPreference(userId, json.toJson(new Prefs(value))));
        } else {
            row.setPrefs(json.toJson(new Prefs(value)));
            preferenceRepository.save(row);
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
