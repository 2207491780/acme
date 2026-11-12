import { useState } from 'react';
import { Text, View } from 'react-native';
import { fmtDate, platformShort, statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, ProgressBar, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** A3 发布单详情全屏（APP 形态）：产物 / 时间线 / 关联发布。 */
export default function ReleaseDetailScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const apps = useStore((s) => s.state.apps);
  const [releaseId, setReleaseId] = useState(releases[0]?.id ?? '');
  const [flash, showFlash] = useFlash();

  const release = releases.find((r) => r.id === releaseId);
  const app = apps.find((a) => a.id === release?.appId);
  const related = releases.filter((r) => r.appId === release?.appId && r.id !== release?.id).slice(0, 3);

  return (
    <ProtoShell title="发布单详情" domain="发布单域 A3" desc="完整信息、产物与时间线；下载为模拟演示。" onBack={onBack} flash={flash}>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {releases.slice(0, 8).map((r) => (
          <Chip key={r.id} label={r.id} active={r.id === releaseId} onPress={() => setReleaseId(r.id)} />
        ))}
      </View>

      {release ? (
        <>
          <Section title={`${release.id} · v${release.version}`}>
            <Text style={{ color: palette.text, fontSize: 15, fontWeight: '700', marginBottom: 6 }}>{release.title}</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
              <Tag label={statusLabel(release.status)} color={TONE_COLOR[statusTone(release.status)]} />
              <Tag label={platformShort(release.platform)} color={TONE_COLOR[release.platform === 'web' ? 'info' : release.platform === 'desktop' ? 'violet' : 'success']} />
              <Tag label={release.env} color={TONE_COLOR.muted} />
            </View>
            {release.status === 'building' ? (
              <ProgressBar percent={release.progress} color={TONE_COLOR[statusTone(release.status)]} />
            ) : null}
            <Muted style={{ fontSize: 12, marginTop: 8 }}>
              {app?.name} · {release.owner} · 优先级 P{release.priority}
            </Muted>
            <Text style={{ color: palette.text2, fontSize: 12.5, lineHeight: 19, marginTop: 6 }}>{release.notes}</Text>
          </Section>

          <Section title="产物">
            {release.artifacts.length ? (
              release.artifacts.map((a) => (
                <View key={a.name} style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 6 }}>
                  <Text style={{ color: palette.text, fontSize: 12.5, flex: 1 }}>{a.name}</Text>
                  <Muted style={{ fontSize: 11 }}>{a.size}</Muted>
                  <Button label="下载" variant="ghost" small onPress={() => showFlash('原型演示：下载仅本地模拟')} />
                </View>
              ))
            ) : (
              <Muted>构建完成后生成产物。</Muted>
            )}
          </Section>

          <Section title={`时间线（${release.timeline.length}）`}>
            <View style={{ gap: 8 }}>
              {[...release.timeline].reverse().map((t, i) => (
                <View key={i} style={{ flexDirection: 'row', gap: 8 }}>
                  <View style={{ width: 8, height: 8, borderRadius: 99, backgroundColor: palette.border, marginTop: 5 }} />
                  <View style={{ flex: 1 }}>
                    <Text style={{ color: palette.text, fontSize: 12.5 }}>{t.action}</Text>
                    <MonoText>{t.by} · {fmtDate(t.at)}</MonoText>
                  </View>
                </View>
              ))}
            </View>
          </Section>

          {related.length ? (
            <Section title="关联发布（同应用）">
              {related.map((r) => (
                <View key={r.id} style={{ flexDirection: 'row', gap: 6, alignItems: 'center', paddingVertical: 5 }}>
                  <Tag label={statusLabel(r.status)} color={TONE_COLOR[statusTone(r.status)]} />
                  <Text style={{ color: palette.text2, fontSize: 12.5, flex: 1 }} numberOfLines={1}>{r.title}</Text>
                  <MonoText>v{r.version}</MonoText>
                </View>
              ))}
            </Section>
          ) : null}
        </>
      ) : (
        <Muted>请选择发布单。</Muted>
      )}
    </ProtoShell>
  );
}
