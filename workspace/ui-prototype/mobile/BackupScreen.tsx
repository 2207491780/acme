import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { backupSnapshots, fmtFull } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** W4 备份与迁移中心（APP 形态）：快照清单 + 导出（仅本地生效提示）；导入预览见 Web 端。 */
export default function BackupScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const snapshots = useMemo(() => backupSnapshots(state, Date.now()), [state]);
  const [flash, showFlash] = useFlash();

  return (
    <ProtoShell
      title="备份与迁移中心"
      domain="内容与治理域 W4"
      desc="快照清单与导出；导入预览 + 确认（真实 IMPORT）见 Web 端完整版。"
      onBack={onBack}
      flash={flash}
    >
      <KpiRow>
        <Kpi label="快照数" value={snapshots.length} tone="info" />
        <Kpi label="当前发布单" value={state.releases.length} tone="violet" />
        <Kpi label="当前应用" value={state.apps.length} tone="success" />
      </KpiRow>

      <Section title="备份快照（确定性演示）">
        {snapshots.map((s) => (
          <View key={s.id} style={styles.row}>
            <View style={{ flex: 1, gap: 2 }}>
              <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center' }}>
                <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>{s.id}</Text>
                {s.tone === 'success' ? <Tag label="最近" color={TONE_COLOR.success} /> : null}
              </View>
              <MonoText color={palette.muted}>{fmtFull(s.createdAt)} · {s.sizeKb} KB</MonoText>
            </View>
            <Button
              label="恢复"
              variant="ghost"
              small
              onPress={() => showFlash('原型演示：恢复前请先导出（W4 纪律）')}
            />
          </View>
        ))}
      </Section>

      <Button label="导出当前数据" onPress={() => showFlash('原型演示：导出为真实数据下载（Web 端同款）')} />

      <Section title="导入纪律（W4）">
        <MonoText color={palette.text2}>先导出 → 再导入预览 → Popconfirm 确认 → IMPORT 拒收非法数据。</MonoText>
        <Muted style={{ fontSize: 11, marginTop: 6 }}>导入走正式 IMPORT 口径，可真实写入 store（见 Web 端完整版）。</Muted>
      </Section>
    </ProtoShell>
  );
}

const styles = {
  row: {
    flexDirection: 'row' as const,
    justifyContent: 'space-between' as const,
    alignItems: 'center' as const,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: palette.border,
    gap: 8,
  },
};
