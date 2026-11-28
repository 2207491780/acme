import { useEffect, useMemo, useState } from 'react';
import { View, Text, Pressable, TextInput, Image, StyleSheet } from 'react-native';
import type { ReactNode } from 'react';
import { useLocaleStore, type Accent, type NeutralPalette, type ThemeMode } from '@acme/core';
import { useStore } from '../store/useStore';
import { useAuthStore } from '../store/authStore';
import { useMeStore } from '../store/meStore';
import { useBSessionStore } from '../store/bSessionStore';
import { ApiError } from '../api/client';
import { authApi } from '../api/auth';
import { profileApi } from '../api/profile';
import { registerPushTokenAfterLogin } from '../utils/push';
import { useAccent, useOnAccent } from '../hooks';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';
import { Card, Screen, Title, Eyebrow, Muted, Tag, Button, toast } from '../components/ui';
import { ACCENTS, ACCENT_ORDER, TONE_COLOR } from '../theme';

type SetStyles = ReturnType<typeof makeStyles>;

function Toggle({
  value,
  onChange,
  label,
  styles,
}: {
  value: boolean;
  onChange: (v: boolean) => void;
  label: string;
  styles: SetStyles;
}) {
  const accent = useAccent();
  const { palette } = useAppTheme();
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="switch"
      accessibilityState={{ checked: value }}
      hitSlop={{ top: 9, bottom: 9 }}
      onPress={() => onChange(!value)}
      style={[
        styles.toggle,
        { backgroundColor: value ? accent : palette.elevated, borderColor: value ? accent : palette.border },
      ]}
    >
      <View style={[styles.knob, { transform: [{ translateX: value ? 22 : 2 }] }]} />
    </Pressable>
  );
}

function SettingRow({
  label,
  desc,
  control,
  styles,
}: {
  label: string;
  desc: string;
  control: ReactNode;
  styles: SetStyles;
}) {
  return (
    <View style={styles.settingRow}>
      <View style={{ flex: 1 }}>
        <Text style={styles.settingLabel}>{label}</Text>
        <Muted style={{ fontSize: 12 }}>{desc}</Muted>
      </View>
      {control}
    </View>
  );
}

/** 主题分段选择（THEME-3 契约⑦三态：跟随系统/浅色/深色；手动 light/dark 即脱离 system，
 *  即时生效并写入共享状态）。 */
function ThemeSegmented({ value, styles }: { value: ThemeMode; styles: SetStyles }) {
  const preferences = useStore((s) => s.state.settings.preferences);
  const dispatch = useStore((s) => s.dispatch);
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const options: { key: ThemeMode; labelKey: string }[] = [
    { key: 'system', labelKey: 'settings.mobile.themeSystem' },
    { key: 'light', labelKey: 'settings.mobile.themeLight' },
    { key: 'dark', labelKey: 'settings.mobile.themeDark' },
  ];
  return (
    <View style={styles.themeSegment}>
      {options.map((option) => {
        const selected = value === option.key;
        return (
          <Pressable
            key={option.key}
            accessibilityRole="button"
            accessibilityState={{ selected }}
            accessibilityLabel={t(option.labelKey)}
            onPress={() =>
              dispatch({
                type: 'UPDATE_SETTINGS',
                patch: { preferences: { ...preferences, mode: option.key } },
              })
            }
            style={[
              styles.themeOption,
              selected ? { backgroundColor: palette.text, borderColor: palette.text } : null,
            ]}
          >
            <Text style={[styles.themeOptionText, { color: selected ? palette.bg : palette.text2 }]}>
              {t(option.labelKey)}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/** 界面语言分段选择（契约③：core localeStore 单一动因切换 zh-CN / en-US）。 */
function LocaleSegmented({ styles }: { styles: SetStyles }) {
  const locale = useLocaleStore((s) => s.locale);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const options: { key: 'zh-CN' | 'en-US'; labelKey: string }[] = [
    { key: 'zh-CN', labelKey: 'nav.userMenu.languageZh' },
    { key: 'en-US', labelKey: 'nav.userMenu.languageEn' },
  ];
  return (
    <View style={styles.themeSegment}>
      {options.map((option) => {
        const selected = locale === option.key;
        return (
          <Pressable
            key={option.key}
            accessibilityRole="button"
            accessibilityState={{ selected }}
            accessibilityLabel={t(option.labelKey)}
            onPress={() => setLocale(option.key)}
            style={[
              styles.themeOption,
              selected ? { backgroundColor: palette.text, borderColor: palette.text } : null,
            ]}
          >
            <Text style={[styles.themeOptionText, { color: selected ? palette.bg : palette.text2 }]}>
              {t(option.labelKey)}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

// ————— 员工账号区（REQ-20261119-082531 契约⑧）：资料精简版 / 改密 / 会话下线其他 / 通知开关 —————
// 全部走 /admin/v1 认证面（profileApi，B 令牌显式头），故整区以 B 会话（员工账密登录）解锁；
// 未登录员工账号时仅展示引导文案（AC-8 改密卡显隐语义）。

/** 头像展示（精简版，无上传）：有 avatarUrl 渲染圆形图片，否则昵称首字符 accent 底占位（契约⑦同语义）。 */
function MiniAvatar({ uri, name, size = 44 }: { uri: string | null; name: string; size?: number }) {
  const accent = useAccent();
  const onAccentHex = useOnAccent();
  const { palette } = useAppTheme();
  if (uri) {
    return (
      <Image
        source={{ uri }}
        accessibilityLabel={name || 'avatar'}
        style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: palette.elevated }}
      />
    );
  }
  const initial = (name ?? '').trim().charAt(0).toUpperCase();
  return (
    <View
      accessibilityLabel={name || 'avatar'}
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        backgroundColor: accent,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Text style={{ color: onAccentHex, fontWeight: '700', fontSize: Math.round(size * 0.45), lineHeight: size * 0.5 }}>
        {initial || '?'}
      </Text>
    </View>
  );
}

/** 资料卡（精简版）：头像展示 + 昵称编辑（GET /me 回显；PUT /me 提交 nickname）。 */
function EmployeeProfileCard({ styles }: { styles: SetStyles }) {
  const { t } = useTranslation();
  const bSession = useBSessionStore((s) => s.session);
  const [nickname, setNickname] = useState(bSession?.nickname ?? '');
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // 画像回显（B 令牌 GET /me）：失败降级 B 会话昵称展示，不阻塞设置页
  useEffect(() => {
    let alive = true;
    profileApi.me()
      .then((view) => {
        if (!alive) return;
        if (typeof view?.user?.nickname === 'string' && view.user.nickname) {
          setNickname(view.user.nickname);
        }
        setAvatarUrl(view?.user?.avatarUrl ?? null);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, []);

  const saveNickname = () => {
    const next = nickname.trim();
    if (!next || next.length > 32) {
      toast(t('profile.errors.profileInvalid'), 'danger');
      return;
    }
    setSaving(true);
    profileApi.meUpdate({ nickname: next })
      .then(() => toast(t('profile.mobile.nicknameSaved'), 'success'))
      .catch((ex: unknown) => toast(ex instanceof Error ? ex.message : t('common.errors.operationFailed'), 'danger'))
      .finally(() => setSaving(false));
  };

  return (
    <Card>
      <Text style={styles.cardTitle}>{t('profile.cards.profile')}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
        <MiniAvatar uri={avatarUrl} name={nickname || bSession?.username || ''} />
        <View style={{ flex: 1 }}>
          <Text style={styles.settingLabel}>{t('profile.fields.nickname')}</Text>
          <Muted style={{ fontSize: 12 }}>{t('profile.mobile.profileDesc')}</Muted>
        </View>
      </View>
      <TextInput
        value={nickname}
        onChangeText={setNickname}
        placeholder={t('profile.fields.nickname')}
        accessibilityLabel={t('profile.fields.nickname')}
        maxLength={32}
        style={styles.profileInput}
      />
      <Button small label={t('profile.mobile.saveNickname')} onPress={saveNickname} disabled={saving} testID="profile-nickname-save" />
    </Card>
  );
}

/** 安全卡（改密表单）：PUT /admin/v1/auth/me/password；成功后其他设备会话由服务端全部下线。 */
function EmployeePasswordCard({ styles }: { styles: SetStyles }) {
  const { t } = useTranslation();
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [changing, setChanging] = useState(false);

  const submit = () => {
    if (!oldPassword || !newPassword || !confirmPassword) {
      return;
    }
    if (newPassword !== confirmPassword) {
      toast(t('profile.toasts.passwordMismatch'), 'danger');
      return;
    }
    setChanging(true);
    profileApi.selfPassword({ oldPassword, newPassword })
      .then(() => {
        setOldPassword('');
        setNewPassword('');
        setConfirmPassword('');
        toast(t('profile.mobile.passwordChanged'), 'success');
      })
      .catch((ex: unknown) => {
        // 改密域全码（REQ-20261128-051442 附录 A.4）：OLD_PASSWORD_INVALID=07-0001 / SAME_PASSWORD=07-0002
        if (ex instanceof ApiError && ex.code === 'platform-auth-07-0001') {
          toast(t('profile.errors.oldPasswordInvalid'), 'danger');
          return;
        }
        if (ex instanceof ApiError && ex.code === 'platform-auth-07-0002') {
          toast(t('profile.errors.samePassword'), 'danger');
          return;
        }
        toast(ex instanceof Error ? ex.message : t('common.errors.operationFailed'), 'danger');
      })
      .finally(() => setChanging(false));
  };

  return (
    <Card>
      <Text style={styles.cardTitle}>{t('profile.cards.security')}</Text>
      <Muted style={{ fontSize: 12 }}>{t('profile.mobile.securityDesc')}</Muted>
      <TextInput
        value={oldPassword}
        onChangeText={setOldPassword}
        placeholder={t('profile.fields.oldPassword')}
        accessibilityLabel={t('profile.fields.oldPassword')}
        secureTextEntry
        autoCapitalize="none"
        style={styles.profileInput}
      />
      <TextInput
        value={newPassword}
        onChangeText={setNewPassword}
        placeholder={t('profile.fields.newPassword')}
        accessibilityLabel={t('profile.fields.newPassword')}
        secureTextEntry
        autoCapitalize="none"
        style={styles.profileInput}
      />
      <TextInput
        value={confirmPassword}
        onChangeText={setConfirmPassword}
        placeholder={t('profile.fields.confirmPassword')}
        accessibilityLabel={t('profile.fields.confirmPassword')}
        secureTextEntry
        autoCapitalize="none"
        style={styles.profileInput}
      />
      <Button small label={t('profile.actions.changePassword')} onPress={submit} disabled={changing} testID="profile-password-submit" />
    </Card>
  );
}

/** 会话卡（登出其他设备）：DELETE /admin/v1/auth/me/sessions/others（保留当前设备）。 */
function EmployeeSessionsCard({ styles }: { styles: SetStyles }) {
  const { t } = useTranslation();
  const [kicking, setKicking] = useState(false);

  const kickOthers = () => {
    setKicking(true);
    profileApi.selfKickOthers()
      .then(() => toast(t('profile.toasts.othersKicked'), 'success'))
      .catch((ex: unknown) => toast(ex instanceof Error ? ex.message : t('common.errors.operationFailed'), 'danger'))
      .finally(() => setKicking(false));
  };

  return (
    <Card>
      <Text style={styles.cardTitle}>{t('profile.session.listTitle')}</Text>
      <SettingRow
        label={t('profile.actions.kickOthers')}
        desc={t('profile.mobile.sessionOthersDesc')}
        styles={styles}
        control={
          <Button small variant="danger" label={t('profile.actions.kickOthers')} onPress={kickOthers} disabled={kicking} testID="profile-kick-others" />
        }
      />
    </Card>
  );
}

/** 通知卡（messagePush 偏好开关，契约⑧通知开关接 prefs API）：开启后尝试注册推送令牌（projectId 降级）。 */
function NotificationPrefsCard({ styles }: { styles: SetStyles }) {
  const { t } = useTranslation();
  const [enabled, setEnabled] = useState<boolean | null>(null); // null=偏好加载中

  useEffect(() => {
    let alive = true;
    profileApi.mePreferences()
      .then((view) => {
        if (alive) setEnabled(view?.messagePush === true);
      })
      .catch(() => {
        if (alive) setEnabled(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  const onToggle = (checked: boolean) => {
    profileApi.mePreferencesUpdate({ messagePush: checked })
      .then(() => {
        setEnabled(checked);
        toast(t('profile.mobile.pushSaved'), 'success');
        if (checked) {
          // 开启即注册推送令牌（契约⑧）：无 EAS projectId 时降级跳过并告知
          void registerPushTokenAfterLogin().then((outcome) => {
            if (outcome === 'registered') {
              toast(t('profile.mobile.pushRegistered'), 'success');
            } else if (outcome === 'skipped-no-project') {
              toast(t('profile.mobile.pushSkipNoProject'), 'info');
            }
          });
        }
      })
      .catch((ex: unknown) => {
        setEnabled((cur) => (cur === null ? cur : !checked)); // 失败回滚开关态
        toast(ex instanceof Error ? ex.message : t('common.errors.operationFailed'), 'danger');
      });
  };

  return (
    <Card>
      <Text style={styles.cardTitle}>{t('profile.cards.notification')}</Text>
      <SettingRow
        label={t('profile.notification.messagePush')}
        desc={t('profile.mobile.pushDesc')}
        styles={styles}
        control={
          <Toggle
            label={t('profile.notification.messagePush')}
            value={enabled === true}
            styles={styles}
            onChange={onToggle}
          />
        }
      />
    </Card>
  );
}

export function SettingsScreen() {
  // 外观偏好（THEME-3 契约⑧）：全部读取点脱离投影字段，走 preferences 唯一事实源
  const preferences = useStore((s) => s.state.settings.preferences);
  const state = useStore((s) => s.state);
  const dispatch = useStore((s) => s.dispatch);
  const resetData = useStore((s) => s.resetData);
  const session = useAuthStore((s) => s.session);
  const clearSession = useAuthStore((s) => s.clear);
  // 员工账号区解锁依据（REQ-20261119-040838 B 会话）：员工账密登录后展示资料/改密/会话/通知卡
  const bSession = useBSessionStore((s) => s.session);
  const accent = useAccent();
  const onAccentHex = useOnAccent();
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const styles = useMemo(() => makeStyles(palette), [palette]);

  const handleLogout = () => {
    authApi
      .logout()
      .catch(() => undefined) // 登出幂等：令牌已失效也清空本地会话回登录屏
      .finally(() => {
        clearSession();
        useMeStore.getState().clear();
        // App 壳 AuthNavigator 据 session 复位回登录 Stack；此处仅反馈结果
        toast(t('settings.mobile.logoutDone'), 'success');
      });
  };

  return (
    <Screen>
      <View style={{ gap: 6 }}>
        <Eyebrow>{t('settings.head.eyebrow')}</Eyebrow>
        <Title>{t('settings.head.title')}</Title>
        <Muted>{t('settings.mobile.sub')}</Muted>
      </View>

      <Card>
        <Text style={styles.cardTitle}>{t('settings.appearance.title')}</Text>
        <SettingRow
          label={t('settings.appearance.theme')}
          desc={t('settings.appearance.themeDesc')}
          styles={styles}
          control={<ThemeSegmented value={preferences.mode} styles={styles} />}
        />
        <SettingRow
          label={t('settings.appearance.accent')}
          desc={t('settings.appearance.accentDesc')}
          styles={styles}
          control={<View />}
        />
        {/* 12 预设色板（THEME-3 契约⑦）：预设即选中即放弃自定义取色；customAccent 经后端同步
            生效并以选中态高亮呈现（一期无取色器 UI，纯展示 chip）。 */}
        <View style={styles.swatchRow}>
          {ACCENT_ORDER.map((name: Accent) => {
            const selected = preferences.customAccent == null && preferences.accent === name;
            return (
              <Pressable
                key={name}
                accessibilityLabel={t('settings.mobile.accentAria', { name })}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                hitSlop={4}
                onPress={() =>
                  dispatch({
                    type: 'UPDATE_SETTINGS',
                    patch: { preferences: { ...preferences, accent: name } },
                  })
                }
                style={[
                  styles.swatch,
                  { backgroundColor: ACCENTS[name], borderColor: selected ? palette.text : 'transparent' },
                ]}
              >
                {selected ? <View style={[styles.swatchDot, { backgroundColor: onAccentHex }]} /> : null}
              </Pressable>
            );
          })}
          {preferences.customAccent ? (
            <View
              accessible
              accessibilityLabel={t('settings.appearance.accentCustom')}
              accessibilityRole="button"
              accessibilityState={{ selected: true }}
              style={[
                styles.swatch,
                { backgroundColor: preferences.customAccent, borderColor: palette.text },
              ]}
            >
              <View style={[styles.swatchDot, { backgroundColor: onAccentHex }]} />
            </View>
          ) : null}
        </View>
        <SettingRow
          label={t('settings.mobile.motionLabel')}
          desc={t('settings.mobile.motionDesc')}
          styles={styles}
          control={
            <Toggle
              label={t('settings.mobile.motionLabel')}
              value={preferences.motionEnabled}
              styles={styles}
              onChange={(v) =>
                dispatch({
                  type: 'UPDATE_SETTINGS',
                  patch: { preferences: { ...preferences, motionEnabled: v } },
                })
              }
            />
          }
        />
      </Card>

      <Card>
        <Text style={styles.cardTitle}>{t('settings.language.title')}</Text>
        <SettingRow
          label={t('settings.language.label')}
          desc={t('settings.language.desc')}
          styles={styles}
          control={<LocaleSegmented styles={styles} />}
        />
      </Card>

      <Card>
        <Text style={styles.cardTitle}>{t('settings.data.title')}</Text>
        <View style={styles.statRow}>
          <View style={styles.statBox}>
            <Text style={[styles.statN, { color: accent }]}>{state.apps.length}</Text>
            <Muted style={{ fontSize: 12 }}>{t('settings.mobile.stats.apps')}</Muted>
          </View>
        </View>
        <SettingRow
          label={t('settings.data.reset')}
          desc={t('settings.data.resetDesc')}
          styles={styles}
          control={
            <Button
              small
              variant="danger"
              label={t('settings.data.resetAction')}
              onPress={() => {
                resetData();
                toast(t('settings.data.resetDone'), 'success');
              }}
            />
          }
        />
        <Muted style={{ fontSize: 12 }}>{t('settings.mobile.exportPending')}</Muted>
      </Card>

      <Card>
        <Text style={styles.cardTitle}>{t('settings.mobile.session')}</Text>
        <SettingRow
          label={session ? t('settings.mobile.signedInAs', { phone: session.phone }) : t('settings.mobile.signedOut')}
          desc={t('settings.mobile.sessionDesc')}
          styles={styles}
          control={
            <Button small variant="danger" label={t('nav.userMenu.logout')} onPress={handleLogout} />
          }
        />
      </Card>

      {/* 员工账号区（REQ-20261119-082531 契约⑧）：B 会话解锁四卡；否则仅引导 */}
      {bSession ? (
        <>
          <EmployeeProfileCard styles={styles} />
          <EmployeePasswordCard styles={styles} />
          <EmployeeSessionsCard styles={styles} />
          <NotificationPrefsCard styles={styles} />
        </>
      ) : (
        <Card>
          <Text style={styles.cardTitle}>{t('profile.mobile.accountTitle')}</Text>
          <Muted style={{ fontSize: 12 }}>{t('profile.mobile.accountLockedDesc')}</Muted>
        </Card>
      )}

      <Card>
        <Text style={styles.cardTitle}>{t('settings.runtime.label')}</Text>
        <View style={styles.runtimeRow}>
          <Muted style={{ flex: 1 }}>{t('settings.mobile.runtimeInfo')}</Muted>
          <Tag label="Mobile" color={TONE_COLOR.success} />
        </View>
      </Card>
    </Screen>
  );
}

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    cardTitle: { color: p.text, fontSize: 16, fontWeight: '700' },
    profileInput: {
      borderWidth: 1,
      borderColor: p.border,
      borderRadius: 10,
      paddingHorizontal: 12,
      minHeight: 44,
      fontSize: 14,
      color: p.text,
    },
    settingRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 16,
      paddingVertical: 6,
    },
    settingLabel: { color: p.text, fontSize: 14, fontWeight: '600' },
    swatchRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
    swatch: {
      width: 36,
      height: 36,
      borderRadius: 99,
      borderWidth: 3,
      alignItems: 'center',
      justifyContent: 'center',
    },
    swatchDot: { width: 10, height: 10, borderRadius: 99 },
    themeSegment: { flexDirection: 'row', borderWidth: 1, borderColor: p.border, borderRadius: 10, overflow: 'hidden' },
    themeOption: {
      paddingHorizontal: 14,
      paddingVertical: 7,
      minHeight: 44,
      justifyContent: 'center',
      borderRightWidth: 1,
      borderRightColor: p.border,
    },
    themeOptionText: { fontSize: 12, fontWeight: '600' },
    toggle: { width: 46, height: 26, borderRadius: 99, borderWidth: 1, justifyContent: 'center' },
    knob: { width: 20, height: 20, borderRadius: 99, backgroundColor: p.text },
    statRow: { flexDirection: 'row', gap: 10 },
    statBox: {
      flex: 1,
      backgroundColor: p.elevated,
      borderRadius: 12,
      paddingVertical: 12,
      alignItems: 'center',
      gap: 2,
    },
    statN: { fontSize: 24, fontWeight: '800' },
    runtimeRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  });
