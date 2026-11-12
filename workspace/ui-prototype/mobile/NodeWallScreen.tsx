import { useMemo, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { buildNodes, NODE_STATUS_META, type NodeStatus, type Tone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const STATUS_ORDER: NodeStatus[] = ['building', 'healthy', 'idle', 'error'];
const STATUS_TONE: Record<NodeStatus, Tone> = {
  building: 'info',
  healthy: 'success',
  idle: 'muted',
  error: 'danger',
};

/** V6 节点状态墙（APP 形态）：格子墙 + 点击查看详情（演示集群）。 */
export default function NodeWallScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [flash, showFlash] = useFlash();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const nodes = useMemo(() => buildNodes(state, Date.now(), 24), [state]);
  const counts = useMemo(() => {
    const map = new Map<NodeStatus, number>();
    for (const n of nodes) map.set(n.status, (map.get(n.status) ?? 0) + 1);
    return map;
  }, [nodes]);
  const current = nodes.find((n) => n.id === selectedId);

  return (
    <ProtoShell
      title="节点状态墙"
      domain="可视化看板 V6"
      desc="颜色 = 状态，数字 = 负载；构建中的节点挂着当前发布单。"
      onBack={onBack}
      flash={flash}
    >
      <KpiRow>
        {STATUS_ORDER.map((s) => (
          <Kpi key={s} label={NODE_STATUS_META[s].label} value={counts.get(s) ?? 0} tone={STATUS_TONE[s]} />
        ))}
      </KpiRow>

      <Section title={`节点（${nodes.length}）`}>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {nodes.map((n) => (
            <Pressable
              key={n.id}
              accessibilityLabel={`节点 ${n.name}，${NODE_STATUS_META[n.status].label}，负载 ${n.load}%`}
              accessibilityRole="button"
              onPress={() => {
                setSelectedId(n.id);
                showFlash(`${n.name}：${NODE_STATUS_META[n.status].label} · 负载 ${n.load}%${n.job ? ` · ${n.job}` : ''}`);
              }}
              style={{
                width: 64,
                height: 64,
                borderRadius: 10,
                borderWidth: selectedId === n.id ? 2 : 1,
                borderColor: selectedId === n.id ? '#5aa7a0' : palette.border,
                backgroundColor: `${TONE_COLOR[STATUS_TONE[n.status]]}33`,
                alignItems: 'center',
                justifyContent: 'center',
                gap: 2,
              }}
            >
              <Text style={{ color: palette.text2, fontSize: 13, fontWeight: '700' }}>{n.status === 'error' ? '✕' : n.load}</Text>
              <MonoText>{n.name}</MonoText>
            </Pressable>
          ))}
        </View>
        <Muted style={{ fontSize: 11, marginTop: 8 }}>点击任意节点查看详情（本地演示状态）。</Muted>
      </Section>

      {current ? (
        <Section title={`节点详情 · ${current.name}`}>
          <MonoText color={palette.text}>状态：{NODE_STATUS_META[current.status].label}</MonoText>
          <MonoText color={palette.text}>负载：{current.load}%</MonoText>
          <MonoText color={palette.text}>当前任务：{current.job ?? '—'}</MonoText>
          <Button label="清除选择" variant="ghost" small onPress={() => setSelectedId(null)} />
        </Section>
      ) : null}
    </ProtoShell>
  );
}
