import { useEffect, useState } from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import type { ReactNode } from 'react';
import type { Accent } from '@acme/core';
import { useStore } from '../store/useStore';
import { useAuthStore } from '../store/authStore';
import { authApi } from '../api/auth';
import { useAccent } from '../hooks';
import { Card, Screen, Title, Eyebrow, Muted, Tag, Button } from '../components/ui';
import { ACCENTS, ACCENT_ORDER, TONE_COLOR, palette, onAccent } from '../theme';

function Toggle({ value, onChange, label }: { value: boolean; onChange: (v: boolean) => void; label: string }) {
  const accent = useAccent();
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="switch"
      accessibilityState={{ checked: value }}
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

function SettingRow({ label, desc, control }: { label: string; desc: string; control: ReactNode }) {
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

export function SettingsScreen() {
  const settings = useStore((s) => s.state.settings);
  const state = useStore((s) => s.state);
  const dispatch = useStore((s) => s.dispatch);
  const resetData = useStore((s) => s.resetData);
  const session = useAuthStore((s) => s.session);
  const clearSession = useAuthStore((s) => s.clear);
  const accent = useAccent();
  const [logoutFlash, setLogoutFlash] = useState<string | null>(null);

  const handleLogout = () => {
    authApi
      .logout()
      .catch(() => undefined) // 登出幂等：令牌已失效也清空本地会话回登录屏
      .finally(() => {
        clearSession();
        setLogoutFlash('已退出登录');
      });
  };
  const [flash, setFlash] = useState<string | null>(null);

  useEffect(() => {
    if (!flash) return;
    const t = setTimeout(() => setFlash(null), 1800);
    return () => clearTimeout(t);
  }, [flash]);

  return (
    <Screen>
      <View style={{ gap: 6 }}>
        <Eyebrow>Preferences</Eyebrow>
        <Title>设置</Title>
        <Muted>强调色与动效即时生效并写入共享状态。移动端采用统一深色主题。</Muted>
      </View>

      <Card>
        <Text style={styles.cardTitle}>外观</Text>
        <View style={styles.settingRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.settingLabel}>强调色</Text>
            <Muted style={{ fontSize: 12 }}>影响按钮、导航与高亮</Muted>
          </View>
        </View>
        <View style={styles.swatchRow}>
          {ACCENT_ORDER.map((name: Accent) => {
            const selected = settings.accent === name;
            return (
              <Pressable
                key={name}
                accessibilityLabel={`强调色 ${name}`}
                accessibilityRole="button"
                accessibilityState={{ selected }}
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
          label="构建动效"
          desc="开启后构建进度实时推进（TICK）"
          control={
            <Toggle
              label="构建动效"
              value={settings.motion}
              onChange={(v) => dispatch({ type: 'UPDATE_SETTINGS', patch: { motion: v } })}
            />
          }
        />
      </Card>

      <Card>
        <Text style={styles.cardTitle}>数据管理</Text>
        <View style={styles.statRow}>
          <View style={styles.statBox}>
            <Text style={[styles.statN, { color: accent }]}>{state.releases.length}</Text>
            <Muted style={{ fontSize: 12 }}>发布单</Muted>
          </View>
          <View style={styles.statBox}>
            <Text style={[styles.statN, { color: accent }]}>{state.apps.length}</Text>
            <Muted style={{ fontSize: 12 }}>应用</Muted>
          </View>
          <View style={styles.statBox}>
            <Text style={[styles.statN, { color: accent }]}>{state.savedViews.length}</Text>
            <Muted style={{ fontSize: 12 }}>保存视图</Muted>
          </View>
        </View>
        <SettingRow
          label="重置演示数据"
          desc="恢复初始种子数据"
          control={
            <Button
              small
              variant="danger"
              label="重置"
              onPress={() => {
                resetData();
                setFlash('已重置为演示数据');
              }}
            />
          }
        />
        {flash ? <Text style={[styles.flash, { color: TONE_COLOR.success }]}>{flash}</Text> : null}
        <Muted style={{ fontSize: 12 }}>导出 / 导入将在接入 expo-file-system 后启用。</Muted>
      </Card>

      <Card>
        <Text style={styles.cardTitle}>登录信息</Text>
        <SettingRow
          label={`已登录：${session?.phone ?? '未登录'}`}
          desc="双令牌经安全存储持久化；access 过期前自动刷新轮换"
          control={
            <Button small variant="danger" label="退出登录" onPress={handleLogout} />
          }
        />
        {logoutFlash ? <Text style={[styles.flash, { color: TONE_COLOR.success }]}>{logoutFlash}</Text> : null}
      </Card>

      <Card>
        <Text style={styles.cardTitle}>运行环境</Text>
        <View style={styles.runtimeRow}>
          <Muted style={{ flex: 1 }}>React Native + Expo · 共享 @acme/core 业务层</Muted>
          <Tag label="Mobile" color={TONE_COLOR.success} />
        </View>
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  cardTitle: { color: palette.text, fontSize: 16, fontWeight: '700' },
  settingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 16,
    paddingVertical: 6,
  },
  settingLabel: { color: palette.text, fontSize: 14, fontWeight: '600' },
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
  toggle: { width: 46, height: 26, borderRadius: 99, borderWidth: 1, justifyContent: 'center' },
  knob: { width: 20, height: 20, borderRadius: 99, backgroundColor: palette.text },
  statRow: { flexDirection: 'row', gap: 10 },
  statBox: {
    flex: 1,
    backgroundColor: palette.elevated,
    borderRadius: 12,
    paddingVertical: 12,
    alignItems: 'center',
    gap: 2,
  },
  statN: { fontSize: 24, fontWeight: '800' },
  flash: { fontSize: 13, fontWeight: '600' },
  runtimeRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
});