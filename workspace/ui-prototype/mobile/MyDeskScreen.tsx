import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { fmtDate, memberProfile, MEMBERS, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoAvatar, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const IDENTITY_DEFAULT = MEMBERS.find((m) => m.role === 'owner')?.name ?? MEMBERS[0].name;

/** U3 我的工作台（APP 形态）：待我审批 / 我负责的进行中 / 我的动态；身份可切换（原型仅本地生效）。 */
export default function MyDeskScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [identity, setIdentity] = useState(IDENTITY_DEFAULT);
  const [decided, setDecided] = useState<Record<string, 'approved' | 'rejected'>>({});
  const [flash, showFlash] = useFlash();
  const profile = useMemo(() => memberProfile(state, identity, Date.now()), [state, identity]);

  if (!profile) return <Muted>成员不在册。</Muted>;

  const activeOwned = profile.ownedReleases.filter((r) => ['building', 'testing', 'approved'].includes(r.status));
  const todo = profile.pendingApprovals.filter((p) => !decided[`${p.releaseId}-${p.stageKey}`]);

  const decide = (key: string, decision: 'approved' | 'rejected', releaseId: string) => {
    setDecided((cur) => ({ ...cur, [key]: decision }));
    showFlash(`原型演示：${releaseId} ${decision === 'approved' ? '已通过' : '已驳回'}（仅本地生效）`);
  };

  return (
    <ProtoShell
      title="我的工作台"
      domain="用户域 U3"
      desc="个人视角聚合：待我处理 → 我负责的 → 我的动态；身份可切换走查。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 4 }}>
        <ProtoAvatar name={identity} size={40} />
        <View style={{ flex: 1 }}>
          <Text style={{ color: palette.text, fontSize: 15, fontWeight: '800' }}>{identity}</Text>
          <MonoText>当前身份（原型演示，成熟化接登录态）</MonoText>
        </View>
      </View>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {MEMBERS.map((m) => (
          <Button key={m.id} label={m.name} variant={m.name === identity ? 'primary' : 'ghost'} small onPress={() => setIdentity(m.name)} />
        ))}
      </View>

      <KpiRow>
        <Kpi label="待我审批" value={todo.length} tone="warning" />
        <Kpi label="进行中" value={activeOwned.length} tone="violet" />
        <Kpi label="已发布" value={profile.ownedReleases.filter((r) => r.status === 'published').length} tone="success" />
      </KpiRow>

      <Section title={`待我审批（${todo.length}）`}>
        {todo.length === 0 ? (
          <Muted>全部处理完毕，或当前身份无审批权责。</Muted>
        ) : (
          todo.map((p) => {
            const key = `${p.releaseId}-${p.stageKey}`;
            return (
              <View key={key} style={{ marginBottom: 10, gap: 6 }}>
                <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                  <Tag label={p.stageLabel} color={TONE_COLOR.warning} />
                  <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>{p.releaseId}</Text>
                  <Text style={{ color: palette.text2, fontSize: 12, flex: 1 }} numberOfLines={1}>{p.releaseTitle}</Text>
                </View>
                <View style={{ flexDirection: 'row', gap: 10 }}>
                  <Button label="通过" small onPress={() => decide(key, 'approved', p.releaseId)} />
                  <Button label="驳回" variant="danger" small onPress={() => decide(key, 'rejected', p.releaseId)} />
                </View>
              </View>
            );
          })
        )}
      </Section>

      <Section title={`我负责的进行中（${activeOwned.length}）`}>
        {activeOwned.length === 0 ? (
          <Muted>暂无。</Muted>
        ) : (
          activeOwned.map((r) => (
            <View key={r.id} style={{ flexDirection: 'row', gap: 6, alignItems: 'center', paddingVertical: 5 }}>
              <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} />
              <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
              <MonoText>{r.id}</MonoText>
            </View>
          ))
        )}
      </Section>

      <Section title={`我的最近动态（${Math.min(profile.audit.length, 6)}）`}>
        {profile.audit.slice(0, 6).map((e) => (
          <View key={e.id} style={{ flexDirection: 'row', gap: 8, alignItems: 'center', paddingVertical: 4 }}>
            <Text style={{ color: palette.muted, fontSize: 11 }}>{fmtDate(e.at)}</Text>
            <Text style={{ color: palette.text2, fontSize: 12, flex: 1 }} numberOfLines={1}>{e.action} · {e.target}</Text>
          </View>
        ))}
        {profile.audit.length === 0 ? <Muted>暂无动态。</Muted> : null}
      </Section>
    </ProtoShell>
  );
}
