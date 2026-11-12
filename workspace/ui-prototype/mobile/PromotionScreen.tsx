import { useState } from 'react';
import { Text, View } from 'react-native';
import { ENVS, envLabel, PROMOTION_COLUMNS, platformShort, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** B3 环境晋升看板（APP 形态）：按环境分列 + 晋升/退回按钮（仅本地生效）。 */
export default function PromotionScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [localEnv, setLocalEnv] = useState<Record<string, (typeof ENVS)[number]>>({});
  const [flash, showFlash] = useFlash();
  const envOf = (id: string, fallback: (typeof ENVS)[number]) => localEnv[id] ?? fallback;

  const move = (releaseId: string, from: (typeof ENVS)[number], dir: 1 | -1) => {
    const to = ENVS[ENVS.indexOf(from) + dir];
    if (!to) return;
    setLocalEnv((cur) => ({ ...cur, [releaseId]: to }));
    showFlash(`原型演示：${releaseId} ${envLabel(from)} → ${envLabel(to)}`);
  };

  return (
    <ProtoShell
      title="环境晋升看板"
      domain="流程配置域 B3"
      desc="dev → test → prod 三列流转；failed 单禁止晋升。"
      onBack={onBack}
      flash={flash}
    >
      {PROMOTION_COLUMNS.map(({ env, label }, colIdx) => {
        const cards = releases.filter((r) => envOf(r.id, r.env) === env && r.status !== 'cancelled');
        return (
          <Section key={env} title={`${label}（${cards.length}）`}>
            {cards.length === 0 ? (
              <Muted>空列。</Muted>
            ) : (
              <View style={{ gap: 8 }}>
                {cards.map((r) => (
                  <View key={r.id} style={styles.card}>
                    <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 4, flexWrap: 'wrap' }}>
                      <Tag label={r.id} color={TONE_COLOR.muted} />
                      <Tag label={platformShort(r.platform)} color={TONE_COLOR.violet} />
                      <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} />
                    </View>
                    <Text style={{ color: palette.text, fontSize: 13, marginBottom: 8 }}>{r.title}</Text>
                    <View style={{ flexDirection: 'row', gap: 10 }}>
                      <Button
                        label="退回"
                        variant="ghost"
                        small
                        disabled={colIdx === 0}
                        onPress={() => move(r.id, envOf(r.id, r.env), -1)}
                      />
                      <Button
                        label="晋升"
                        small
                        disabled={colIdx === PROMOTION_COLUMNS.length - 1 || r.status === 'failed'}
                        onPress={() => move(r.id, envOf(r.id, r.env), 1)}
                      />
                    </View>
                    <MonoText color={palette.muted}>当前环境：{envLabel(envOf(r.id, r.env))}</MonoText>
                  </View>
                ))}
              </View>
            )}
          </Section>
        );
      })}
    </ProtoShell>
  );
}

const styles = {
  card: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    backgroundColor: palette.elevated,
  },
};
