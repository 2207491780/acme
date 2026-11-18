import { View, Text, StyleSheet } from 'react-native';
import type { ReactNode } from 'react';
import { useMeStore } from '../store/meStore';
import { Card, Muted, Button } from './ui';
import { TONE_COLOR, palette } from '../theme';

/**
 * Screen 级权限门禁（REQ-20261114-120110 AMB 裁决；语义对齐 web/src/App.tsx 范本，fail-closed）：
 * 权限源 = meStore.permissions（服务端画像）。三态：
 *   ① 画像加载失败 → 错误提示 + 重试入口；
 *   ② 画像未加载（加载中/未拉取）→ loading 占位（fail-closed，不渲染 children）；
 *   ③ 已加载缺权限码 → 403 兜底提示 + 「返回」入口（onBack 存在时）；
 * 有权限 → 渲染 children。
 */
export function PermissionGate({
  permission,
  children,
  onBack,
}: {
  permission: string;
  /** 可选仅为兼容 createElement 第三参传 children 的调用形（类型层面）；fail-closed 语义不变。 */
  children?: ReactNode;
  onBack?: () => void;
}) {
  const me = useMeStore((s) => s.me);
  const loading = useMeStore((s) => s.loading);
  const error = useMeStore((s) => s.error);

  if (!me) {
    if (error && !loading) {
      return (
        <Card style={styles.card}>
          <Text style={[styles.title, { color: TONE_COLOR.danger }]}>画像加载失败</Text>
          <Muted>{error}</Muted>
          <View style={styles.actionRow}>
            <Button small variant="ghost" label="重试" onPress={() => void useMeStore.getState().load()} />
          </View>
        </Card>
      );
    }
    return (
      <View style={styles.loading}>
        <Muted>权限校验中…</Muted>
      </View>
    );
  }

  if (!me.permissions.includes(permission)) {
    return (
      <Card style={styles.card}>
        <Text style={styles.title}>403 · 无权访问</Text>
        <Muted>
          当前账号缺少权限 `{permission}`，如需访问请联系管理员开通权限。
        </Muted>
        {onBack ? (
          <View style={styles.actionRow}>
            <Button small variant="ghost" label="返回" onPress={onBack} />
          </View>
        ) : null}
      </Card>
    );
  }

  return <>{children}</>;
}

const styles = StyleSheet.create({
  card: { margin: 16, gap: 8 },
  title: { color: palette.text, fontSize: 16, fontWeight: '800' },
  actionRow: { flexDirection: 'row', justifyContent: 'flex-end' },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
