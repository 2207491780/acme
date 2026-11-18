import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { auditTrail, fmtDate, type AuditEntry } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { Chip, ProtoShell, Section, ToneDot } from './kit';
import { palette } from '../../theme';

/** C3 活动审计（APP 形态）：按人筛选的操作留痕流。 */
export default function AuditScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [entries] = useState<AuditEntry[]>(() => auditTrail({ releases }, Date.now()));
  const [actor, setActor] = useState('all');
  const actors = useMemo(() => ['all', ...new Set(entries.map((e) => e.actor))], [entries]);
  const filtered = entries.filter((e) => actor === 'all' || e.actor === actor);

  return (
    <ProtoShell
      title="活动审计"
      domain="观测域 C3"
      desc="谁在什么时候对哪个发布单做了什么；导出能力见 Web 端。"
      onBack={onBack}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 }}>
        {actors.map((a) => (
          <Chip key={a} label={a === 'all' ? '全部' : a} active={actor === a} onPress={() => setActor(a)} />
        ))}
      </View>

      <Section title={`操作留痕（${filtered.length}）`}>
        <View>
          {filtered.map((e) => (
            <View key={e.id} style={{ flexDirection: 'row', gap: 8, paddingVertical: 7, borderBottomWidth: 1, borderBottomColor: palette.border }}>
              <ToneDot tone={e.tone} />
              <View style={{ flex: 1, gap: 1 }}>
                <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                  <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>{e.actor}</Text>
                  <Tag label={e.action} color={palette.muted} />
                  <Text style={{ color: palette.text, fontSize: 12, fontFamily: undefined }}>{e.target}</Text>
                </View>
                <Muted style={{ fontSize: 11 }}>{e.detail} · {fmtDate(e.at)}</Muted>
              </View>
            </View>
          ))}
        </View>
      </Section>
      <Muted style={{ fontSize: 11 }}>审计流为确定性演示数据；成熟化时由真实动作事件写入并保留 90 天。</Muted>
    </ProtoShell>
  );
}
