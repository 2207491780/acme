import { useCallback, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Pressable, ScrollView, Text, View, StyleSheet } from 'react-native';
import { TONES, type Tone } from '@acme/core';
import { palette, onAccent } from '../../theme';
import { useAccent } from '../../hooks';
import { Card, Tag } from '../../components/ui';

/** 原型壳组件：与 Web 端 PageShell 同构（徽标/返回/说明），供全部原型屏复用。 */

export function ProtoShell({
  title,
  domain,
  desc,
  onBack,
  flash,
  children,
}: {
  title: string;
  domain: string;
  desc: string;
  onBack: () => void;
  flash?: string | null;
  children: ReactNode;
}) {
  const accent = useAccent();
  return (
    <View style={{ flex: 1, backgroundColor: palette.bg }}>
      <View style={styles.topbar}>
        <Pressable
          accessibilityLabel="返回原型索引"
          accessibilityRole="button"
          onPress={onBack}
          style={({ pressed }) => [styles.back, pressed && { opacity: 0.6 }]}
        >
          <Text style={[styles.backText, { color: accent }]}>{'‹ 原型索引'}</Text>
        </Pressable>
        <View style={[styles.badge, { borderColor: `${TONES.warning}66`, backgroundColor: `${TONES.warning}1f` }]}>
          <Text style={{ color: TONES.warning, fontSize: 11, fontWeight: '700' }}>原型预览</Text>
        </View>
        <Tag label={domain} color={accent} />
      </View>
      <ScrollView
        style={{ flex: 1 }}
        contentContainerStyle={{ padding: 16, paddingBottom: 40, gap: 14 }}
        showsVerticalScrollIndicator={false}
      >
        <View style={{ gap: 4 }}>
          <Text style={{ color: palette.text, fontSize: 21, fontWeight: '800' }}>{title}</Text>
          <Text style={{ color: palette.text2, fontSize: 12.5, lineHeight: 19 }}>{desc}</Text>
          <Text style={{ color: palette.muted, fontSize: 11 }}>演示数据 · 不落库 · 交互仅本地生效</Text>
        </View>
        {flash ? (
          <View style={[styles.flash, { borderColor: `${accent}33` }]}>
            <Text style={{ color: accent, fontSize: 12 }}>{flash}</Text>
          </View>
        ) : null}
        {children}
      </ScrollView>
    </View>
  );
}

/** 轻量提示条：替代 Web 端 message，两秒后自动消失。 */
export function useFlash(): [string | null, (text: string) => void] {
  const [flash, setFlash] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const show = useCallback((text: string) => {
    setFlash(text);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setFlash(null), 2200);
  }, []);
  return [flash, show];
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card>
      <Text style={{ color: palette.text, fontSize: 14, fontWeight: '700', marginBottom: 10 }}>{title}</Text>
      {children}
    </Card>
  );
}

export function Kpi({ label, value, tone = 'info' }: { label: string; value: string | number; tone?: Tone }) {
  return (
    <View style={[styles.kpi, { borderColor: `${TONES[tone]}33` }]}>
      <Text style={{ color: palette.muted, fontSize: 11 }}>{label}</Text>
      <Text style={{ color: TONES[tone], fontSize: 21, fontWeight: '800', marginTop: 2 }}>{value}</Text>
    </View>
  );
}

export function KpiRow({ children }: { children: ReactNode }) {
  return <View style={styles.kpiRow}>{children}</View>;
}

export function BarList({ items }: { items: Array<{ name: string; count: number; tone: Tone }> }) {
  const max = Math.max(1, ...items.map((x) => x.count));
  return (
    <View style={{ gap: 8 }}>
      {items.map((item) => (
        <View key={item.name} style={{ gap: 4 }}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <Text style={{ color: palette.text2, fontSize: 12, flex: 1 }} numberOfLines={1}>{item.name}</Text>
            <Text style={{ color: palette.text, fontSize: 12, fontWeight: '700' }}>{item.count}</Text>
          </View>
          <View style={styles.track}>
            <View style={{ height: '100%', width: `${(item.count / max) * 100}%`, borderRadius: 999, backgroundColor: TONES[item.tone] }} />
          </View>
        </View>
      ))}
    </View>
  );
}

export function Chip({
  label,
  active,
  onPress,
}: {
  label: string;
  active: boolean;
  onPress: () => void;
}) {
  const accent = useAccent();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected: active }}
      onPress={onPress}
      style={({ pressed }) => [
        styles.chip,
        active ? { backgroundColor: `${accent}29`, borderColor: accent } : null,
        pressed && { opacity: 0.7 },
      ]}
    >
      <Text style={{ color: active ? accent : palette.text2, fontSize: 12, fontWeight: '600' }}>{label}</Text>
    </Pressable>
  );
}

export function ToneDot({ tone }: { tone: Tone }) {
  return <View style={{ width: 9, height: 9, borderRadius: 99, backgroundColor: TONES[tone], marginTop: 5 }} />;
}

/** 首字母头像（目录/详情/工作台共用）。 */
export function ProtoAvatar({ name, size = 30 }: { name: string; size?: number }) {
  const accent = useAccent();
  return (
    <View
      accessibilityLabel={`${name} 头像`}
      style={{ width: size, height: size, borderRadius: 99, backgroundColor: accent, alignItems: 'center', justifyContent: 'center' }}
    >
      <Text style={{ color: onAccent, fontSize: size * 0.42, fontWeight: '800' }}>{name.slice(0, 1)}</Text>
    </View>
  );
}

export function MonoText({ children, color }: { children: ReactNode; color?: string }) {
  return <Text style={{ color: color ?? palette.muted, fontSize: 11, fontFamily: undefined }}>{children}</Text>;
}

export const PROTO_ON_ACCENT = onAccent;

const styles = StyleSheet.create({
  topbar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 16,
    paddingTop: 10,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: palette.border,
  },
  back: { paddingVertical: 6, paddingRight: 6 },
  backText: { fontSize: 13, fontWeight: '700' },
  badge: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 3 },
  flash: { borderWidth: 1, borderRadius: 10, paddingVertical: 8, paddingHorizontal: 12 },
  kpi: { flex: 1, backgroundColor: palette.elevated, borderRadius: 12, borderWidth: 1, padding: 10, gap: 2 },
  kpiRow: { flexDirection: 'row', gap: 10, flexWrap: 'wrap' },
  track: { height: 6, borderRadius: 999, backgroundColor: palette.elevated, overflow: 'hidden' },
  chip: { borderWidth: 1, borderColor: palette.border, borderRadius: 999, paddingHorizontal: 12, paddingVertical: 6, backgroundColor: palette.surface },
});
