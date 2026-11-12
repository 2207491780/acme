import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const COLUMNS = ['id', 'title', 'env', 'owner', 'version', 'updated'] as const;
const COL_LABEL: Record<(typeof COLUMNS)[number], string> = {
  id: 'ID', title: '标题', env: '环境', owner: '负责人', version: '版本', updated: '更新',
};

function fmtShort(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/** X2 表格进阶（APP 形态）：列显示自定义 + 展开行时间线。 */
export default function AdvancedTableScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [visible, setVisible] = useState<string[]>([...COLUMNS]);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const rows = useMemo(() => state.releases.slice(0, 8), [state.releases]);

  const toggleCol = (key: string) =>
    setVisible((cur) => (cur.includes(key) ? cur.filter((k) => k !== key) : [...cur, key]));

  return (
    <ProtoShell
      title="表格进阶模式"
      domain="横向专项 X2"
      desc="列显示自定义 + 展开行时间线；密度与固定列见 Web 端完整版。"
      onBack={onBack}
    >
      <Section title="列显示（点选切换）">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {COLUMNS.map((c) => (
            <Chip key={c} label={COL_LABEL[c]} active={visible.includes(c)} onPress={() => toggleCol(c)} />
          ))}
        </View>
        <MonoText color={palette.muted}>显示 {visible.length}/{COLUMNS.length} 列</MonoText>
      </Section>

      <Section title={`发布单（${rows.length}）`}>
        {rows.map((r) => (
          <View key={r.id} style={{ marginBottom: 10 }}>
            <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
              {visible.includes('id') ? <Tag label={r.id} color={TONE_COLOR.muted} /> : null}
              {visible.includes('env') ? <Tag label={r.env} color={TONE_COLOR.warning} /> : null}
              {visible.includes('owner') ? <Tag label={r.owner} color={TONE_COLOR.violet} /> : null}
              {visible.includes('version') ? <Tag label={`v${r.version}`} color={TONE_COLOR.info} /> : null}
              {visible.includes('status') ? <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} /> : null}
            </View>
            {visible.includes('title') ? (
              <Text style={{ color: palette.text, fontSize: 13, marginTop: 4 }} numberOfLines={1}>{r.title}</Text>
            ) : null}
            {visible.includes('updated') ? <MonoText color={palette.muted}>{fmtShort(r.updatedAt)}</MonoText> : null}
            <Button
              label={expandedId === r.id ? '收起时间线' : `展开时间线（${r.timeline.length}）`}
              variant="ghost"
              small
              onPress={() => setExpandedId((cur) => (cur === r.id ? null : r.id))}
            />
            {expandedId === r.id ? (
              <View style={{ gap: 4, paddingTop: 6 }}>
                {r.timeline.map((t, i) => (
                  <MonoText key={i} color={palette.text2}>{fmtShort(t.at)} · {t.action}（{t.by}）</MonoText>
                ))}
              </View>
            ) : null}
          </View>
        ))}
      </Section>
      <Muted style={{ fontSize: 11 }}>万行级虚拟滚动为成熟化方向（当前演示量级普通渲染即可）。</Muted>
    </ProtoShell>
  );
}
