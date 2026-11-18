import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { pipelineChain, statusLabel, type PipelineStageView } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR, ACCENTS } from '../../theme';

const STAGE_COLOR: Record<PipelineStageView['state'], string> = {
  done: TONE_COLOR.success,
  active: ACCENTS.cyan,
  pending: palette.muted,
  failed: TONE_COLOR.danger,
};

const FILTERS = ['all', 'building', 'testing', 'approved', 'draft', 'failed'] as const;

/** V1 阶段管道图（APP 形态）：横向节点链，构建节点实时进度。 */
export default function PipelineScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>('all');
  const releases = useMemo(
    () => state.releases.filter((r) => filter === 'all' || r.status === filter).slice(0, 8),
    [state.releases, filter],
  );

  return (
    <ProtoShell
      title="阶段管道图"
      domain="可视化看板 V1"
      desc="每条发布单的 草稿→构建→测试→审批→发布 链路；构建进度由 TICK 推进。"
      onBack={onBack}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 }}>
        {FILTERS.map((f) => (
          <Chip key={f} label={f === 'all' ? '全部' : statusLabel(f)} active={filter === f} onPress={() => setFilter(f)} />
        ))}
      </View>

      {releases.map((r) => {
        const chain = pipelineChain(r);
        return (
          <Section key={r.id} title={`${r.id} · ${statusLabel(r.status)}`}>
            <Text style={{ color: palette.text, fontSize: 13.5, marginBottom: 10 }}>{r.title}</Text>
            <View style={{ flexDirection: 'row', alignItems: 'flex-start' }}>
              {chain.map((s, i) => (
                <View key={s.key} style={{ alignItems: 'center', flex: 1 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', width: '100%' }}>
                    <View style={{ width: 16, height: 16, borderRadius: 99, borderWidth: 2, borderColor: STAGE_COLOR[s.state], backgroundColor: s.state === 'active' ? STAGE_COLOR[s.state] : 'transparent' }} />
                    {i < chain.length - 1 ? (
                      <View style={{ flex: 1, height: 2, backgroundColor: s.state === 'done' ? TONE_COLOR.success : palette.border }} />
                    ) : null}
                  </View>
                  <Text style={{ color: s.state === 'pending' ? palette.muted : palette.text, fontSize: 10.5, marginTop: 6, textAlign: 'center' }}>{s.label}</Text>
                  {s.note ? <MonoText color={STAGE_COLOR[s.state]}>{s.note}</MonoText> : null}
                </View>
              ))}
            </View>
            {r.status === 'building' ? <MonoText color={TONE_COLOR.info}>{`构建进度 ${r.progress}%`}</MonoText> : null}
            {r.status === 'failed' ? <Tag label="测试阶段失败，链路中止" color={TONE_COLOR.danger} /> : null}
          </Section>
        );
      })}
      {releases.length === 0 ? <Muted>没有匹配的发布单。</Muted> : null}
    </ProtoShell>
  );
}
