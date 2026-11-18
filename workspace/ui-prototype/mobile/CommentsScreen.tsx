import { useState } from 'react';
import { TextInput, Text, View } from 'react-native';
import { relTime, threads as loadThreads, type ProtoComment } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted } from '../../components/ui';
import { Chip, ProtoShell, Section, useFlash } from './kit';
import { palette } from '../../theme';

function CommentNode({ node, depth = 0 }: { node: ProtoComment; depth?: number }) {
  return (
    <View style={{ marginLeft: depth * 20, marginTop: 8 }}>
      <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>
        {node.author} <Text style={{ color: palette.muted, fontWeight: '400', fontSize: 11 }}>{relTime(node.at)}</Text>
      </Text>
      <Text style={{ color: palette.text2, fontSize: 13, lineHeight: 19, marginTop: 2 }}>{node.text}</Text>
      {node.replies.map((r) => (
        <CommentNode key={r.id} node={r} depth={depth + 1} />
      ))}
    </View>
  );
}

/** D3 发布单评论（APP 形态）：thread + 回复 + @提及提示（仅本地生效）。 */
export default function CommentsScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [releaseId, setReleaseId] = useState(releases[0]?.id ?? '');
  const [draft, setDraft] = useState('');
  const [extra, setExtra] = useState<Record<string, ProtoComment[]>>({});
  const [flash, showFlash] = useFlash();

  const base = loadThreads({ releases }, Date.now());
  const thread = base.find((t) => t.releaseId === releaseId);
  const items = [...(thread?.items ?? []), ...(extra[releaseId] ?? [])];

  const submit = () => {
    if (!draft.trim()) return;
    const mention = draft.includes('@');
    setExtra((cur) => ({
      ...cur,
      [releaseId]: [
        ...(cur[releaseId] ?? []),
        { id: `local-${Date.now()}`, author: '我', at: new Date().toISOString(), text: draft.trim(), replies: [] },
      ],
    }));
    setDraft('');
    showFlash(mention ? '原型演示：已提交，@ 提及将推送通知' : '原型演示：评论已提交（仅本地生效）');
  };

  return (
    <ProtoShell
      title="发布单评论"
      domain="协作域 D3"
      desc="发布单内讨论 thread；@ 提及联动通知。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {releases.slice(0, 8).map((r) => (
          <Chip key={r.id} label={r.id} active={r.id === releaseId} onPress={() => setReleaseId(r.id)} />
        ))}
      </View>

      {thread ? (
        <Section title={`讨论（${items.length} 条主楼）`}>
          {items.map((c) => (
            <CommentNode key={c.id} node={c} />
          ))}
          <TextInput
            value={draft}
            onChangeText={setDraft}
            placeholder="写下讨论…@ 可提及成员"
            placeholderTextColor={palette.muted}
            multiline
            accessibilityLabel="评论输入"
            style={[styles.input, { color: palette.text }]}
          />
          <Button label="发送" onPress={submit} />
        </Section>
      ) : (
        <Muted>请选择发布单。</Muted>
      )}
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
    minHeight: 64,
    textAlignVertical: 'top' as const,
    fontSize: 13,
    marginBottom: 10,
    marginTop: 10,
  },
};
