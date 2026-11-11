import type { ReactNode } from 'react';
import {
  View,
  Text,
  Pressable,
  ScrollView,
  StyleSheet,
  type StyleProp,
  type ViewStyle,
  type TextStyle,
} from 'react-native';
import { palette, TONE_COLOR } from '../theme';
import { useAccent, useOnAccent } from '../hooks';

export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[styles.card, style]}>{children}</View>;
}

export function Tag({ label, color }: { label: string; color: string }) {
  return (
    <View style={[styles.tag, { borderColor: `${color}66`, backgroundColor: `${color}1f` }]}>
      <Text style={[styles.tagText, { color }]}>{label}</Text>
    </View>
  );
}

export function Button({
  label,
  onPress,
  variant = 'primary',
  small,
}: {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'ghost' | 'danger';
  small?: boolean;
}) {
  const accent = useAccent();
  const onAccent = useOnAccent();
  const bg = variant === 'primary' ? accent : 'transparent';
  const fg = variant === 'primary' ? onAccent : variant === 'danger' ? TONE_COLOR.danger : palette.text2;
  const border = variant === 'primary' ? accent : variant === 'danger' ? `${TONE_COLOR.danger}66` : palette.border;
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [
        styles.btn,
        small && styles.btnSmall,
        { backgroundColor: bg, borderColor: border },
        pressed && { opacity: 0.65 },
      ]}
    >
      <Text style={[styles.btnText, { color: fg }, small && { fontSize: 12 }]}>{label}</Text>
    </Pressable>
  );
}

export function Muted({ children, style }: { children: ReactNode; style?: StyleProp<TextStyle> }) {
  return <Text style={[styles.muted, style]}>{children}</Text>;
}

export function ProgressBar({ percent, color }: { percent: number; color: string }) {
  const width = `${Math.max(0, Math.min(100, percent))}%` as const;
  return (
    <View style={styles.progressTrack}>
      <View style={[styles.progressFill, { width, backgroundColor: color }]} />
    </View>
  );
}

export function Screen({ children }: { children: ReactNode }) {
  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={styles.screenContent}
      showsVerticalScrollIndicator={false}
    >
      {children}
    </ScrollView>
  );
}

export function Title({ children }: { children: ReactNode }) {
  return <Text style={styles.title}>{children}</Text>;
}

export function Eyebrow({ children }: { children: ReactNode }) {
  const accent = useAccent();
  return <Text style={[styles.eyebrow, { color: accent }]}>{children}</Text>;
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: palette.bg },
  screenContent: { padding: 16, paddingBottom: 32, gap: 14 },
  card: {
    backgroundColor: palette.surface,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: palette.border,
    padding: 16,
    gap: 10,
  },
  tag: {
    alignSelf: 'flex-start',
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  tagText: { fontSize: 12, fontWeight: '600' },
  btn: {
    borderRadius: 12,
    borderWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 11,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnSmall: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 10 },
  btnText: { fontSize: 14, fontWeight: '700' },
  muted: { color: palette.muted, fontSize: 13 },
  title: { color: palette.text, fontSize: 24, fontWeight: '800' },
  eyebrow: { fontSize: 12, fontWeight: '700', letterSpacing: 2, textTransform: 'uppercase' },
  progressTrack: {
    height: 6,
    borderRadius: 999,
    backgroundColor: palette.elevated,
    overflow: 'hidden',
  },
  progressFill: { height: '100%', borderRadius: 999 },
});