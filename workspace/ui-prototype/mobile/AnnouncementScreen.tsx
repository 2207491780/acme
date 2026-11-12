import { useMemo, useState } from 'react';
import { TextInput, Text, View } from 'react-native';
import { announcements, docCards, fmtDate, type Announcement } from '@acme/core';
import { Button, Tag } from '../../components/ui';
import { Kpi, KpiRow, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** W1 公告与规范中心（APP 形态）：置顶公告 + 文档索引 + 发布（仅本地生效）。 */
export default function AnnouncementScreen({ onBack }: { onBack: () => void }) {
  const now = Date.now();
  const base = useMemo(() => announcements(now), [now]);
  const docs = useMemo(() => docCards(now), [now]);
  const [extra, setExtra] = useState<Announcement[]>([]);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [flash, showFlash] = useFlash();
  const list = [...extra, ...base];

  const publish = () => {
    if (!title.trim() || !body.trim()) {
      showFlash('标题与正文均为必填');
      return;
    }
    setExtra((cur) => [{ id: `local-${Date.now()}`, title: title.trim(), body: body.trim(), author: '我', at: new Date().toISOString(), pinned: false, tone: 'info' as const }, ...cur]);
    setTitle('');
    setBody('');
    showFlash('原型演示：公告已发布（仅本地生效）');
  };

  return (
    <ProtoShell title="公告与规范中心" domain="内容与治理域 W1" desc="团队公告与规范文档；正文支持 Markdown（原型纯文本渲染）。" onBack={onBack} flash={flash}>
      <KpiRow>
        <Kpi label="公告" value={list.length} tone="info" />
        <Kpi label="置顶" value={list.filter((a) => a.pinned).length} tone="warning" />
        <Kpi label="文档" value={docs.length} tone="violet" />
      </KpiRow>

      <Section title="发布公告">
        <TextInput value={title} onChangeText={setTitle} placeholder="公告标题" placeholderTextColor={palette.muted} accessibilityLabel="公告标题" style={styles.input} />
        <TextInput value={body} onChangeText={setBody} placeholder="正文（支持 **加粗** 与换行）" placeholderTextColor={palette.muted} multiline accessibilityLabel="公告正文" style={[styles.input, { minHeight: 64, textAlignVertical: 'top' }]} />
        <Button label="发布公告" onPress={publish} />
      </Section>

      <Section title={`公告（${list.length}）`}>
        {list.map((a) => (
          <View key={a.id} style={[styles.card, a.pinned && { borderColor: TONE_COLOR.warning }]}>
            <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 4 }}>
              {a.pinned ? <Tag label="置顶" color={TONE_COLOR.warning} /> : null}
              <Text style={{ color: palette.text, fontSize: 13.5, fontWeight: '700', flex: 1 }}>{a.title}</Text>
            </View>
            <Text style={{ color: palette.text2, fontSize: 12.5, lineHeight: 19 }}>{a.body}</Text>
            <MonoText color={palette.muted}>{a.author} · {fmtDate(a.at)}</MonoText>
          </View>
        ))}
      </Section>

      <Section title={`规范与文档（${docs.length}）`}>
        {docs.map((d) => (
          <View key={d.id} style={{ flexDirection: 'row', gap: 8, alignItems: 'center', paddingVertical: 6 }}>
            <Text style={{ color: palette.text, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{d.title}</Text>
            <Tag label={d.kind} color={TONE_COLOR.info} />
            <MonoText color={palette.muted}>{fmtDate(d.updatedAt)}</MonoText>
          </View>
        ))}
      </Section>
    </ProtoShell>
  );
}

const styles = {
  input: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    backgroundColor: palette.surface,
    padding: 10,
    fontSize: 13,
    color: palette.text,
    marginBottom: 10,
  },
  card: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    marginBottom: 8,
    backgroundColor: palette.elevated,
  },
};
