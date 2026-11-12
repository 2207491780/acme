import { useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, View, StyleSheet } from 'react-native';
import { buildLog, type LogLine } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const LEVELS: LogLine['level'][] = ['info', 'cmd', 'warn', 'error'];
const LEVEL_COLOR: Record<LogLine['level'], string> = {
  cmd: TONE_COLOR.info,
  info: palette.text2,
  warn: TONE_COLOR.warning,
  error: TONE_COLOR.danger,
};

/** A2 构建日志（APP 形态）：级别过滤 + 重新生成演示。 */
export default function BuildLogScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [releaseId, setReleaseId] = useState(releases[0]?.id ?? '');
  const [levels, setLevels] = useState<LogLine['level'][]>(LEVELS);
  const [runKey, setRunKey] = useState(0);
  const [flash, showFlash] = useFlash();

  const release = releases.find((r) => r.id === releaseId);
  const lines = useMemo(() => (release ? buildLog(release, String(runKey)) : []), [release, runKey]);
  const visible = lines.filter((l) => levels.includes(l.level));

  const toggleLevel = (level: LogLine['level']) =>
    setLevels((cur) => (cur.includes(level) ? cur.filter((x) => x !== level) : [...cur, level]));

  return (
    <ProtoShell
      title="构建日志"
      domain="发布单域 A2"
      desc="级别过滤的日志流；失败单自动注入错误样本。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {releases.slice(0, 8).map((r) => (
          <Chip key={r.id} label={r.id} active={r.id === releaseId} onPress={() => setReleaseId(r.id)} />
        ))}
      </View>

      {release ? (
        <>
          <Section title={`日志过滤 · ${release.id}`}>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
              {LEVELS.map((lv) => (
                <Chip key={lv} label={lv.toUpperCase()} active={levels.includes(lv)} onPress={() => toggleLevel(lv)} />
              ))}
              <Tag label={release.status === 'failed' ? '失败中止' : '运行中'} color={release.status === 'failed' ? TONE_COLOR.danger : TONE_COLOR.info} />
            </View>
            <Button
              label="重新生成日志"
              variant="ghost"
              small
              onPress={() => {
                setRunKey((k) => k + 1);
                showFlash('原型演示：已重新生成日志');
              }}
            />
          </Section>

          <Section title={`日志（${visible.length}/${lines.length} 行）`}>
            <ScrollView style={styles.logBox} showsVerticalScrollIndicator>
              {visible.map((l, i) => (
                <Pressable key={i} accessibilityLabel={`${l.stage} ${l.text}`}>
                  <View style={styles.logRow}>
                    <MonoText color={palette.muted}>{String(l.offsetSec).padStart(3, '0')}s</MonoText>
                    <MonoText color={LEVEL_COLOR[l.level]}>{l.stage}</MonoText>
                    <Text style={{ color: LEVEL_COLOR[l.level], fontSize: 11.5, flex: 1 }} selectable>
                      {l.text}
                    </Text>
                  </View>
                </Pressable>
              ))}
            </ScrollView>
          </Section>
        </>
      ) : (
        <Text style={{ color: palette.muted }}>请选择发布单。</Text>
      )}
    </ProtoShell>
  );
}

const styles = StyleSheet.create({
  logBox: { maxHeight: 300, borderRadius: 10, backgroundColor: palette.bg, borderWidth: 1, borderColor: palette.border },
  logRow: { flexDirection: 'row', gap: 8, paddingVertical: 4, paddingHorizontal: 8, alignItems: 'flex-start' },
});
