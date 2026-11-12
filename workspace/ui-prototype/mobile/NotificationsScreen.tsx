import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { CATEGORY_LABEL, fmtDate, notices, type Notice } from '@acme/core';
import { Button, Muted } from '../../components/ui';
import { Chip, ProtoShell, Section, ToneDot, useFlash } from './kit';
import { palette } from '../../theme';

/** D2 通知中心（APP 形态）：分类过滤 + 已读管理（仅本地生效）。 */
export default function NotificationsScreen({ onBack }: { onBack: () => void }) {
  const [items, setItems] = useState<Notice[]>(() => notices(Date.now()));
  const [filter, setFilter] = useState<'all' | 'unread' | Notice['category']>('all');
  const [flash, showFlash] = useFlash();

  const filtered = items.filter((n) =>
    filter === 'all' ? true : filter === 'unread' ? !n.read : n.category === filter,
  );
  const unread = items.filter((n) => !n.read).length;

  return (
    <ProtoShell
      title="通知中心"
      domain="协作域 D2"
      desc="审批 / 构建 / 提及 / 系统通知聚合收件箱。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 }}>
        {(['all', 'unread', 'approval', 'build', 'mention', 'system'] as const).map((f) => (
          <Chip
            key={f}
            label={f === 'all' ? `全部` : f === 'unread' ? `未读 ${unread}` : CATEGORY_LABEL[f]}
            active={filter === f}
            onPress={() => setFilter(f)}
          />
        ))}
      </View>

      <Section title={`通知（${filtered.length}）`}>
        {filtered.length === 0 ? (
          <Muted>没有通知。</Muted>
        ) : (
          filtered.map((n) => (
            <Pressable
              key={n.id}
              accessibilityLabel={`通知：${n.title}${n.read ? '' : '（未读）'}`}
              onPress={() => setItems((cur) => cur.map((x) => (x.id === n.id ? { ...x, read: true } : x)))}
              style={{ flexDirection: 'row', gap: 8, paddingVertical: 9, borderBottomWidth: 1, borderBottomColor: palette.border, opacity: n.read ? 0.65 : 1 }}
            >
              <ToneDot tone={n.tone} />
              <View style={{ flex: 1, gap: 2 }}>
                <Text style={{ color: palette.text, fontSize: 13, fontWeight: n.read ? '400' : '700' }}>{n.title}</Text>
                <Text style={{ color: palette.text2, fontSize: 12 }}>{n.body}</Text>
                <Muted style={{ fontSize: 11 }}>{CATEGORY_LABEL[n.category]} · {fmtDate(n.at)}</Muted>
              </View>
            </Pressable>
          ))
        )}
      </Section>
      <Button
        label="全部已读"
        variant="ghost"
        onPress={() => {
          setItems((cur) => cur.map((n) => ({ ...n, read: true })));
          showFlash('原型演示：全部已读仅本地生效');
        }}
      />
    </ProtoShell>
  );
}
