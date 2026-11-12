import { useMemo } from 'react';
import { Text, View } from 'react-native';
import { fmtDate, memberProfile, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoAvatar, ProtoShell, Section, ToneDot } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const DECISION_COLOR = { approved: '#6fa287', rejected: '#c47b7b', pending: '#8b929e' } as const;

/** U2 用户详情（APP 形态）：档案 + 负责发布单 + 审批记录 + 留痕。 */
export default function UserDetailScreen({ onBack, memberName }: { onBack: () => void; memberName: string }) {
  const state = useStore((s) => s.state);
  const profile = useMemo(() => memberProfile(state, memberName, Date.now()), [state, memberName]);

  return (
    <ProtoShell
      title={memberName}
      domain="用户域 U2"
      desc="个人工作档案：负责发布单、审批签署、待办与操作留痕。"
      onBack={onBack}
    >
      {!profile ? (
        <Muted>成员不在册。</Muted>
      ) : (
        <>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <ProtoAvatar name={memberName} size={46} />
            <View style={{ flex: 1 }}>
              <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                <Text style={{ color: palette.text, fontSize: 16, fontWeight: '800' }}>{memberName}</Text>
                <Tag label={profile.member.role} color={TONE_COLOR.muted} />
              </View>
              <MonoText>{profile.member.email} · 最近活动 {profile.lastActiveAt ? fmtDate(profile.lastActiveAt) : '—'}</MonoText>
            </View>
          </View>

          <KpiRow>
            <Kpi label="负责发布单" value={profile.ownedReleases.length} tone="info" />
            <Kpi label="进行中" value={profile.ownedReleases.filter((r) => ['draft', 'building', 'testing', 'approved'].includes(r.status)).length} tone="violet" />
            <Kpi label="已发布" value={profile.ownedReleases.filter((r) => r.status === 'published').length} tone="success" />
            <Kpi label="审批待办" value={profile.pendingApprovals.length} tone="warning" />
          </KpiRow>

          <Section title={`负责的发布单（${profile.ownedReleases.length}）`}>
            {profile.ownedReleases.length === 0 ? (
              <Muted>暂无。</Muted>
            ) : (
              profile.ownedReleases.map((r) => (
                <View key={r.id} style={{ flexDirection: 'row', gap: 6, alignItems: 'center', paddingVertical: 5 }}>
                  <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} />
                  <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
                  <MonoText>{r.id}</MonoText>
                </View>
              ))
            )}
          </Section>

          <Section title={`审批待办（${profile.pendingApprovals.length}）`}>
            {profile.pendingApprovals.length === 0 ? (
              <Muted>没有等待处理的审批。</Muted>
            ) : (
              profile.pendingApprovals.map((p) => (
                <View key={`${p.releaseId}-${p.stageKey}`} style={{ flexDirection: 'row', gap: 6, alignItems: 'center', paddingVertical: 5 }}>
                  <Tag label={p.stageLabel} color={TONE_COLOR.warning} />
                  <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{p.releaseTitle}</Text>
                  <MonoText>{p.releaseId}</MonoText>
                </View>
              ))
            )}
          </Section>

          <Section title={`审批签署（${profile.approvalRecords.filter((x) => x.decision !== 'pending').length}）`}>
            {profile.approvalRecords.filter((x) => x.decision !== 'pending').map((p) => (
              <View key={`${p.releaseId}-${p.stageKey}`} style={{ paddingVertical: 6, gap: 2 }}>
                <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                  <Tag label={p.decision === 'approved' ? '已通过' : '已驳回'} color={DECISION_COLOR[p.decision]} />
                  <Text style={{ color: palette.text, fontSize: 12 }}>{p.stageLabel} · {p.releaseId}</Text>
                  {p.at ? <MonoText color={palette.muted}>{fmtDate(p.at)}</MonoText> : null}
                </View>
                <Text style={{ color: palette.text2, fontSize: 12 }}>{p.comment}</Text>
              </View>
            ))}
          </Section>

          <Section title={`操作留痕（${profile.audit.length}）`}>
            {profile.audit.length === 0 ? (
              <Muted>暂无留痕。</Muted>
            ) : (
              profile.audit.map((e) => (
                <View key={e.id} style={{ flexDirection: 'row', gap: 8, alignItems: 'center', paddingVertical: 4 }}>
                  <ToneDot tone={e.tone} />
                  <Text style={{ color: palette.text2, fontSize: 12, flex: 1 }} numberOfLines={1}>
                    {e.action} · {e.target}
                  </Text>
                  <MonoText color={palette.muted}>{fmtDate(e.at)}</MonoText>
                </View>
              ))
            )}
            <Button label="返回用户列表" variant="ghost" small onPress={onBack} />
          </Section>
        </>
      )}
    </ProtoShell>
  );
}
