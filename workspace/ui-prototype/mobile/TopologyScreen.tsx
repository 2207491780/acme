import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { deploymentTopology } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const EDGE_TONE = (label: string) =>
  label.includes('注入') ? TONE_COLOR.violet : label.includes('静态') ? TONE_COLOR.info : TONE_COLOR.success;

/** H2 部署拓扑图（APP 形态）：分层节点卡 + 连线说明列表（RN 无内建 SVG，以分层卡呈现）。 */
export default function TopologyScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const { nodes, edges } = useMemo(() => deploymentTopology(state), [state]);
  const apps = nodes.filter((n) => n.kind === 'app');
  const channels = nodes.filter((n) => n.kind === 'channel');

  return (
    <ProtoShell
      title="部署拓扑图"
      domain="环境域 H2"
      desc="应用与分发渠道的部署关系；桌面复用 Web 产物为核心连线。"
      onBack={onBack}
    >
      <Section title="应用节点">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
          {apps.map((n) => (
            <View key={n.id} style={styles.node}>
              <Text style={{ color: palette.text, fontSize: 13, fontWeight: '700' }}>{n.label}</Text>
              <Tag label={`v${n.version}`} color={TONE_COLOR.info} />
            </View>
          ))}
        </View>
        <Muted style={{ fontSize: 11, marginTop: 6 }}>↓ 连线见下方清单（RN 以分层卡近似图形态）</Muted>
      </Section>

      <Section title="分发渠道">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
          {channels.map((n) => (
            <View key={n.id} style={[styles.node, { borderStyle: 'dashed' }]}>
              <Text style={{ color: palette.text2, fontSize: 13 }}>{n.label}</Text>
            </View>
          ))}
        </View>
      </Section>

      <Section title="连线清单">
        {edges.map((e) => {
          const from = nodes.find((n) => n.id === e.from)!;
          const to = nodes.find((n) => n.id === e.to)!;
          return (
            <View key={`${e.from}-${e.to}`} style={{ flexDirection: 'row', gap: 8, alignItems: 'center', paddingVertical: 6 }}>
              <View style={{ width: 10, height: 10, borderRadius: 3, backgroundColor: EDGE_TONE(e.label) }} />
              <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>{from.label}</Text>
              <Text style={{ color: palette.muted, fontSize: 12 }}>→（{e.label}）→</Text>
              <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>{to.label}</Text>
            </View>
          );
        })}
      </Section>
    </ProtoShell>
  );
}

const styles = {
  node: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 12,
    backgroundColor: palette.elevated,
    alignItems: 'center' as const,
    gap: 6,
    minWidth: 120,
  },
};
