import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { orchestrationGraph, platformShort, statusLabel, statusTone, topoSort } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** A6 批量编排（APP 形态）：拓扑批次视图 + 成环告警。 */
export default function OrchestrationScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const nodes = useMemo(() => orchestrationGraph(state), [state]);
  const { order, cycles } = useMemo(() => topoSort(nodes), [nodes]);
  const byId = new Map(nodes.map((n) => [n.releaseId, n]));
  const batches: typeof nodes[] = [];
  const placed = new Set<string>();
  for (const id of order) {
    const node = byId.get(id)!;
    const batch = batches.findIndex(() => node.dependsOn.every((d) => placed.has(d)));
    if (batch >= 0) batches[batch].push(node);
    else batches.push([node]);
    placed.add(id);
  }

  return (
    <ProtoShell
      title="批量编排"
      domain="发布单域 A6"
      desc="依赖拓扑分批（mobile → web → desktop 桥接）；成环时阻断执行。"
      onBack={onBack}
    >
      {cycles.length > 0 ? (
        <View style={{ borderWidth: 1, borderColor: `${TONE_COLOR.danger}66`, borderRadius: 12, padding: 10, marginBottom: 4 }}>
          <Text style={{ color: TONE_COLOR.danger, fontSize: 12.5, fontWeight: '700' }}>
            存在循环依赖（{cycles.length} 个节点），无法编排
          </Text>
        </View>
      ) : null}

      {nodes.length === 0 ? (
        <Muted>没有可编排的发布单（草稿/构建/测试/待发布自动进入）。</Muted>
      ) : (
        batches.map((batch, i) => (
          <Section key={i} title={`执行批次 ${i + 1}（${batch.length}）`}>
            <View style={{ gap: 8 }}>
              {batch.map((n) => (
                <View key={n.releaseId} style={styles.node}>
                  <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 4 }}>
                    <Tag label={statusLabel(n.status)} color={TONE_COLOR[statusTone(n.status)]} />
                    <Tag label={platformShort(state.apps.find((a) => a.id === n.appId)?.platform ?? 'web')} color={TONE_COLOR.violet} />
                    <MonoText color={palette.text}>{n.releaseId}</MonoText>
                  </View>
                  <Text style={{ color: palette.text, fontSize: 13 }}>{n.title}</Text>
                  <MonoText color={palette.muted}>
                    {n.dependsOn.length ? `依赖：${n.dependsOn.join('、')}` : '无前置依赖，可最先执行'}
                  </MonoText>
                </View>
              ))}
            </View>
          </Section>
        ))
      )}
    </ProtoShell>
  );
}

const styles = {
  node: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    backgroundColor: palette.elevated,
    gap: 3,
  },
};
