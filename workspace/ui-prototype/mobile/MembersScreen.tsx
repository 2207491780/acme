import { useState } from 'react';
import { Text, View } from 'react-native';
import { MEMBERS, PERMISSION_MATRIX, ROLE_LABEL, ROLE_TONE } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** D1 成员与角色（APP 形态）：成员卡 + 权限矩阵。 */
export default function MembersScreen({ onBack }: { onBack: () => void }) {
  const apps = useStore((s) => s.state.apps);
  const [view, setView] = useState<'members' | 'matrix'>('members');
  const [flash, showFlash] = useFlash();

  return (
    <ProtoShell
      title="成员与角色"
      domain="协作域 D1"
      desc="团队成员与角色权限矩阵；审批人池取自这里。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', gap: 8, marginBottom: 4 }}>
        {(['members', 'matrix'] as const).map((v) => (
          <Button key={v} label={v === 'members' ? '成员' : '权限矩阵'} variant={view === v ? 'primary' : 'ghost'} small onPress={() => setView(v)} />
        ))}
      </View>

      {view === 'members' ? (
        MEMBERS.map((m) => (
          <Section key={m.id} title={m.name}>
            <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 6, flexWrap: 'wrap' }}>
              <Tag label={ROLE_LABEL[m.role]} color={TONE_COLOR[ROLE_TONE[m.role]]} />
              <EmailText>{m.email}</EmailText>
            </View>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
              {m.apps.length ? (
                m.apps.map((id) => <Tag key={id} label={apps.find((a) => a.id === id)?.name ?? id} color={palette.muted} />)
              ) : (
                <Muted style={{ fontSize: 12 }}>未分配应用</Muted>
              )}
            </View>
            <Button label="编辑分配（原型）" variant="ghost" small onPress={() => showFlash('原型演示：仅本地生效')} />
          </Section>
        ))
      ) : (
        <Section title="角色 × 权限矩阵">
          {PERMISSION_MATRIX.map((row) => (
            <View key={row.action} style={{ paddingVertical: 7, borderBottomWidth: 1, borderBottomColor: palette.border, gap: 6 }}>
              <Text style={{ color: palette.text, fontSize: 12.5 }}>{row.action}</Text>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
                {(['owner', 'dev', 'qa', 'ops', 'viewer'] as const).map((role) => {
                  const allowed = row.roles.includes(role);
                  return (
                    <Tag
                      key={role}
                      label={`${ROLE_LABEL[role]}${allowed ? ' ✓' : ''}`}
                      color={allowed ? TONE_COLOR[ROLE_TONE[role]] : palette.muted}
                    />
                  );
                })}
              </View>
            </View>
          ))}
        </Section>
      )}
    </ProtoShell>
  );
}

function EmailText({ children }: { children: string }) {
  return <Text style={{ color: palette.muted, fontSize: 11 }}>{children}</Text>;
}
