import { useMemo, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { fmtDate, memberDirectory, ROLE_LABEL, ROLE_TONE } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { Chip, Kpi, KpiRow, MonoText, ProtoAvatar, ProtoShell } from './kit';
import { palette, TONE_COLOR } from '../../theme';
import { useAccent } from '../../hooks';
import UserDetailScreen from './UserDetailScreen';

const ROLES = ['all', 'owner', 'dev', 'qa', 'ops', 'viewer'] as const;

/** U1 用户列表（APP 形态）：成员目录 + 工作负荷，点击行进入内嵌详情（U2）。 */
export default function UserDirectoryScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const accent = useAccent();
  const rows = useMemo(() => memberDirectory(state, Date.now()), [state]);
  const [role, setRole] = useState<(typeof ROLES)[number]>('all');
  const [detailName, setDetailName] = useState<string | null>(null);
  const filtered = rows.filter((row) => role === 'all' || row.member.role === role);
  const totalPending = rows.reduce((n, r) => n + r.pendingApprovals, 0);
  const totalOwned = rows.reduce((n, r) => n + r.ownedTotal, 0);

  if (detailName) {
    return <UserDetailScreen memberName={detailName} onBack={() => setDetailName(null)} />;
  }

  return (
    <ProtoShell
      title="用户列表"
      domain="用户域 U1"
      desc="成员目录与工作负荷；点击成员进入个人详情（U2）。"
      onBack={onBack}
    >
      <KpiRow>
        <Kpi label="成员数" value={rows.length} tone="info" />
        <Kpi label="负责发布单" value={totalOwned} tone="violet" />
        <Kpi label="审批待办" value={totalPending} tone="warning" />
      </KpiRow>

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 }}>
        {ROLES.map((r) => (
          <Chip key={r} label={r === 'all' ? '全部' : ROLE_LABEL[r]} active={role === r} onPress={() => setRole(r)} />
        ))}
      </View>

      {filtered.map((row) => (
        <Pressable
          key={row.member.id}
          accessibilityRole="button"
          accessibilityLabel={`查看 ${row.member.name} 详情`}
          onPress={() => setDetailName(row.member.name)}
          style={({ pressed }) => [styles.card, pressed && { opacity: 0.7, borderColor: `${TONE_COLOR.info}66` }]}
        >
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
            <ProtoAvatar name={row.member.name} />
            <View style={{ flex: 1 }}>
              <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center' }}>
                <Text style={{ color: palette.text, fontSize: 14, fontWeight: '700' }}>{row.member.name}</Text>
                <Tag label={ROLE_LABEL[row.member.role]} color={TONE_COLOR[ROLE_TONE[row.member.role]]} />
              </View>
              <MonoText>{row.member.email}</MonoText>
            </View>
            <Text style={{ color: accent, fontSize: 16 }}>›</Text>
          </View>
          <View style={{ flexDirection: 'row', gap: 10, marginTop: 8, flexWrap: 'wrap' }}>
            <MonoText color={palette.text2}>负责 {row.ownedTotal}（进行 {row.ownedActive}）</MonoText>
            <MonoText color={palette.text2}>发布 {row.ownedPublished}</MonoText>
            {row.pendingApprovals > 0 ? <Tag label={`${row.pendingApprovals} 待办`} color={TONE_COLOR.warning} /> : null}
            <MonoText color={palette.muted}>最近 {row.lastActiveAt ? fmtDate(row.lastActiveAt) : '—'}</MonoText>
          </View>
        </Pressable>
      ))}
      {filtered.length === 0 ? <Muted>没有匹配的成员。</Muted> : null}
    </ProtoShell>
  );
}

const styles = {
  card: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 12,
    backgroundColor: palette.surface,
    gap: 6,
  },
};
