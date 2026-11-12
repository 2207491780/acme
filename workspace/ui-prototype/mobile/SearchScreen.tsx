import { useMemo, useState } from 'react';
import { TextInput, Text, View } from 'react-native';
import { globalSearch, MEMBERS } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const KINDS = ['all', 'release', 'app', 'member'] as const;
const KIND_LABEL = { all: '全部', release: '发布单', app: '应用', member: '成员' } as const;
const KIND_COLOR = { release: TONE_COLOR.info, app: TONE_COLOR.violet, member: TONE_COLOR.success } as const;

/** X1 全局搜索中心（APP 形态）：聚合发布单 / 应用 / 成员的即时结果（F2 数据面）。 */
export default function SearchScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState<(typeof KINDS)[number]>('all');
  const members = useMemo(() => MEMBERS.map((m) => ({ name: m.name, email: m.email })), []);
  const hits = useMemo(() => {
    const all = globalSearch(state, members, query, 40);
    return kind === 'all' ? all : all.filter((h) => h.kind === kind);
  }, [state, members, query, kind]);

  return (
    <ProtoShell
      title="全局搜索中心"
      domain="横向专项 X1"
      desc="聚合发布单 / 应用 / 成员；与 F2 命令面板同数据口径。"
      onBack={onBack}
    >
      <TextInput
        value={query}
        onChangeText={setQuery}
        placeholder="搜发布单 / 应用 / 成员…"
        placeholderTextColor={palette.muted}
        autoFocus
        accessibilityLabel="全局搜索"
        style={styles.input}
      />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
        {KINDS.map((k) => (
          <Chip key={k} label={KIND_LABEL[k]} active={kind === k} onPress={() => setKind(k)} />
        ))}
      </View>

      {!query.trim() ? (
        <Muted>输入关键词开始搜索。</Muted>
      ) : hits.length === 0 ? (
        <Muted>没有匹配的结果。</Muted>
      ) : (
        <Section title={`结果（${hits.length}）`}>
          {hits.map((h) => (
            <View key={`${h.kind}-${h.id}`} style={styles.hit}>
              <Text style={{ color: palette.text, fontSize: 13, fontWeight: '700', flex: 1 }} numberOfLines={1}>{h.title}</Text>
              <View style={{ borderWidth: 1, borderColor: KIND_COLOR[h.kind as keyof typeof KIND_COLOR], borderRadius: 6, paddingHorizontal: 6, paddingVertical: 2 }}>
                <Text style={{ color: KIND_COLOR[h.kind as keyof typeof KIND_COLOR], fontSize: 10 }}>{KIND_LABEL[h.kind as keyof typeof KIND_LABEL]}</Text>
              </View>
              <MonoText>{h.sub}</MonoText>
            </View>
          ))}
        </Section>
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
    padding: 12,
    fontSize: 14,
    color: palette.text,
    marginBottom: 10,
  },
  hit: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: 8,
    paddingVertical: 7,
    borderBottomWidth: 1,
    borderBottomColor: palette.border,
  },
};
