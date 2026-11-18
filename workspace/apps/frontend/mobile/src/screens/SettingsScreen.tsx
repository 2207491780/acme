import { useMemo } from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import type { ReactNode } from 'react';
import { useLocaleStore, type Accent, type NeutralPalette, type Theme } from '@acme/core';
import { useStore } from '../store/useStore';
import { useAuthStore } from '../store/authStore';
import { useMeStore } from '../store/meStore';
import { authApi } from '../api/auth';
import { useAccent } from '../hooks';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';
import { Card, Screen, Title, Eyebrow, Muted, Tag, Button, toast } from '../components/ui';
import { ACCENTS, ACCENT_ORDER, TONE_COLOR, onAccent } from '../theme';

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

/** 主题分段选择（契约②：settings.theme dark/light，即时生效并写入共享状态）。 */
function ThemeSegmented({ value, styles }: { value: Theme; styles: SetStyles }) {
  const dispatch = useStore((s) => s.dispatch);
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const options: { key: Theme; labelKey: string }[] = [
    { key: 'dark', labelKey: 'settings.mobile.themeDark' },
    { key: 'light', labelKey: 'settings.mobile.themeLight' },
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
            onPress={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: option.key } })}
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

export function SettingsScreen() {
  const settings = useStore((s) => s.state.settings);
  const state = useStore((s) => s.state);
  const dispatch = useStore((s) => s.dispatch);
  const resetData = useStore((s) => s.resetData);
  const session = useAuthStore((s) => s.session);
  const clearSession = useAuthStore((s) => s.clear);
  const accent = useAccent();
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
          control={<ThemeSegmented value={settings.theme} styles={styles} />}
        />
        <SettingRow
          label={t('settings.appearance.accent')}
          desc={t('settings.appearance.accentDesc')}
          styles={styles}
          control={<View />}
        />
        <View style={styles.swatchRow}>
          {ACCENT_ORDER.map((name: Accent) => {
            const selected = settings.accent === name;
            return (
              <Pressable
                key={name}
                accessibilityLabel={t('settings.mobile.accentAria', { name })}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                hitSlop={4}
                onPress={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { accent: name } })}
                style={[
                  styles.swatch,
                  { backgroundColor: ACCENTS[name], borderColor: selected ? palette.text : 'transparent' },
                ]}
              >
                {selected ? <View style={styles.swatchDot} /> : null}
              </Pressable>
            );
          })}
        </View>
        <SettingRow
          label={t('settings.mobile.motionLabel')}
          desc={t('settings.mobile.motionDesc')}
          styles={styles}
          control={
            <Toggle
              label={t('settings.mobile.motionLabel')}
              value={settings.motion}
              styles={styles}
              onChange={(v) => dispatch({ type: 'UPDATE_SETTINGS', patch: { motion: v } })}
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
            <Text style={[styles.statN, { color: accent }]}>{state.releases.length}</Text>
            <Muted style={{ fontSize: 12 }}>{t('settings.mobile.stats.releases')}</Muted>
          </View>
          <View style={styles.statBox}>
            <Text style={[styles.statN, { color: accent }]}>{state.apps.length}</Text>
            <Muted style={{ fontSize: 12 }}>{t('settings.mobile.stats.apps')}</Muted>
          </View>
          <View style={styles.statBox}>
            <Text style={[styles.statN, { color: accent }]}>{state.savedViews.length}</Text>
            <Muted style={{ fontSize: 12 }}>{t('settings.mobile.stats.savedViews')}</Muted>
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
    swatchDot: { width: 10, height: 10, borderRadius: 99, backgroundColor: onAccent },
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
