import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  View,
  Text,
  Pressable,
  ScrollView,
  ActivityIndicator,
  StyleSheet,
  type StyleProp,
  type ViewStyle,
  type TextStyle,
} from 'react-native';
import type { Tone } from '@acme/core';
import type { NeutralPalette } from '@acme/core';
import { TONE_COLOR, useAppTheme } from '../theme';
import { useAccent, useOnAccent } from '../hooks';
import { useTranslation } from '../i18n';

/**
 * 主题派生样式（主题化改造统一模式）：模块级 makeStyles(palette) 工厂 + 组件内 useMemo。
 * palette 引用随 core resolvePalette 稳定（dark/light 各一个常量对象），
 * 依赖 [palette] 即可精确 memo（主题切换 → store → ThemeProvider → 全树重渲染新样式）。
 */
type UiStyles = ReturnType<typeof makeStyles>;

function useStyles(): UiStyles {
  const { palette } = useAppTheme();
  return useMemo(() => makeStyles(palette), [palette]);
}

export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const styles = useStyles();
  return <View style={[styles.card, style]}>{children}</View>;
}

export function Tag({ label, color }: { label: string; color: string }) {
  const styles = useStyles();
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
  disabled,
  testID,
}: {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'ghost' | 'danger';
  small?: boolean;
  disabled?: boolean;
  /** 测试定位锚（透传 RN Pressable testID；组件测试约定用语义 testID 而非文案匹配）。 */
  testID?: string;
}) {
  const styles = useStyles();
  const { palette } = useAppTheme();
  const accent = useAccent();
  const onAccent = useOnAccent();
  const [pressed, setPressed] = useState(false);
  const bg = variant === 'primary' ? accent : 'transparent';
  const fg = variant === 'primary' ? onAccent : variant === 'danger' ? TONE_COLOR.danger : palette.text2;
  const border = variant === 'primary' ? accent : variant === 'danger' ? `${TONE_COLOR.danger}66` : palette.border;
  return (
    <Pressable
      onPress={onPress}
      onPressIn={() => setPressed(true)}
      onPressOut={() => setPressed(false)}
      disabled={disabled}
      accessibilityState={{ disabled: !!disabled }}
      testID={testID}
      // style 用静态数组（禁函数式 style）：RN 无碍，且组件级测试的 RN 宿主替身把
      // props 透传到 DOM 元素，函数式 style 会被 react-dom 拒绝。
      style={[
        styles.btn,
        small && styles.btnSmall,
        { backgroundColor: bg, borderColor: border },
        disabled && { opacity: 0.4 },
      ]}
    >
      <Text
        style={[
          styles.btnText,
          { color: fg, opacity: pressed && !disabled ? 0.65 : 1 },
          small && { fontSize: 12 },
        ]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

export function Muted({ children, style }: { children: ReactNode; style?: StyleProp<TextStyle> }) {
  const styles = useStyles();
  return <Text style={[styles.muted, style]}>{children}</Text>;
}

export function ProgressBar({ percent, color }: { percent: number; color: string }) {
  const styles = useStyles();
  const width = `${Math.max(0, Math.min(100, percent))}%` as const;
  return (
    <View style={styles.progressTrack}>
      <View style={[styles.progressFill, { width, backgroundColor: color }]} />
    </View>
  );
}

export function Screen({ children }: { children: ReactNode }) {
  const styles = useStyles();
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
  const styles = useStyles();
  return <Text style={styles.title}>{children}</Text>;
}

export function Eyebrow({ children }: { children: ReactNode }) {
  const accent = useAccent();
  const styles = useStyles();
  return <Text style={[styles.eyebrow, { color: accent }]}>{children}</Text>;
}

/** Loading 原语（契约⑥）：活动指示器 + 文案，全部走主题令牌。 */
export function Loading({ label }: { label?: string }) {
  const styles = useStyles();
  const accent = useAccent();
  const { t } = useTranslation();
  return (
    <View style={styles.center} accessibilityRole="progressbar">
      <ActivityIndicator color={accent} size="small" />
      <Text style={styles.loadingText}>{label ?? t('common.status.loading')}</Text>
    </View>
  );
}

/** Empty 原语（契约⑥）：图标占位（纯 View 几何）+ 文案，全部走主题令牌。 */
export function Empty({ text }: { text?: string }) {
  const styles = useStyles();
  const { t } = useTranslation();
  return (
    <View style={styles.center}>
      <View style={styles.emptyGlyph}>
        <View style={styles.emptyGlyphInner} />
      </View>
      <Text style={styles.emptyText}>{text ?? t('common.mobile.empty')}</Text>
    </View>
  );
}

// ---------------------------------------------------------------- Toast（契约⑥：轻量自绘，禁第三方库）

export interface ToastItem {
  id: number;
  message: string;
  tone: Tone;
}

const TOAST_DURATION_MS = 2500;

type EmitToast = (message: string, tone?: Tone) => void;

/** Provider 挂载后的真实发射器；未挂载时调用为 no-op（保证任意时点调用安全）。 */
let emitToast: EmitToast | null = null;

const noopToast: EmitToast = () => undefined;

const ToastContext = createContext<{ toast: EmitToast }>({ toast: noopToast });

/** 组件内推荐入口（同 context 值）。 */
export function useToast(): { toast: EmitToast } {
  return useContext(ToastContext);
}

/** 顶层 toast 入口：toast(msg, tone)，ToastProvider 顶层挂载，2.5s 自动消失。 */
export function toast(message: string, tone: Tone = 'info'): void {
  emitToast?.(message, tone);
}

let toastSeq = 0;

export function ToastProvider({
  children,
  durationMs = TOAST_DURATION_MS,
}: {
  children: ReactNode;
  /** 自动消失时长（默认 2500ms，契约⑥）；测试可注入短时长避免 fake timers 饿死 React 调度器。 */
  durationMs?: number;
}) {
  const styles = useStyles();
  const { palette } = useAppTheme();
  const [items, setItems] = useState<ToastItem[]>([]);
  useEffect(() => {
    emitToast = (message, tone = 'info') => {
      toastSeq += 1;
      const id = toastSeq;
      setItems((cur) => [...cur, { id, message, tone }]);
      setTimeout(() => {
        setItems((cur) => cur.filter((item) => item.id !== id));
      }, durationMs);
    };
    return () => {
      emitToast = null;
    };
  }, [durationMs]);
  const api = useMemo(() => ({ toast: ((message: string, tone?: Tone) => emitToast?.(message, tone)) as EmitToast }), []);
  return (
    <ToastContext.Provider value={api}>
      {children}
      <View pointerEvents="none" style={styles.toastLayer}>
        {items.map((item) => (
          <View
            key={item.id}
            style={[styles.toast, { backgroundColor: palette.elevated, borderLeftColor: TONE_COLOR[item.tone] }]}
            accessibilityLiveRegion="polite"
          >
            <Text style={[styles.toastText, { color: palette.text }]}>{item.message}</Text>
          </View>
        ))}
      </View>
    </ToastContext.Provider>
  );
}

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    screen: { flex: 1, backgroundColor: p.bg },
    screenContent: { padding: 16, paddingBottom: 32, gap: 14 },
    card: {
      backgroundColor: p.surface,
      borderRadius: 16,
      borderWidth: 1,
      borderColor: p.border,
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
      minHeight: 44,
      alignItems: 'center',
      justifyContent: 'center',
    },
    btnSmall: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 10 },
    btnText: { fontSize: 14, fontWeight: '700' },
    muted: { color: p.muted, fontSize: 13 },
    title: { color: p.text, fontSize: 24, fontWeight: '800' },
    eyebrow: { fontSize: 12, fontWeight: '700', letterSpacing: 2, textTransform: 'uppercase' },
    progressTrack: {
      height: 6,
      borderRadius: 999,
      backgroundColor: p.elevated,
      overflow: 'hidden',
    },
    progressFill: { height: '100%', borderRadius: 999 },
    center: { alignItems: 'center', justifyContent: 'center', gap: 10, padding: 16 },
    loadingText: { color: p.muted, fontSize: 13 },
    emptyGlyph: {
      width: 46,
      height: 46,
      borderRadius: 999,
      borderWidth: 2,
      borderColor: p.surface3,
      alignItems: 'center',
      justifyContent: 'center',
    },
    emptyGlyphInner: {
      width: 12,
      height: 12,
      borderRadius: 3,
      backgroundColor: p.surface3,
    },
    emptyText: { color: p.muted, fontSize: 13 },
    toastLayer: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      alignItems: 'center',
      gap: 8,
      paddingHorizontal: 16,
    },
    toast: {
      width: '100%',
      borderRadius: 12,
      borderWidth: 1,
      borderLeftWidth: 3,
      borderLeftColor: TONE_COLOR.info,
      paddingHorizontal: 12,
      paddingVertical: 10,
      marginTop: 8,
    },
    toastText: { fontSize: 13, lineHeight: 18 },
  });
