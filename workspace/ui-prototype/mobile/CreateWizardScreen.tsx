import { useState } from 'react';
import { TextInput, Text, View } from 'react-native';
import { ENVS, envLabel, platformShort } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const STEPS = ['目标应用', '发布信息', '确认创建', '完成'];

/** R2 新建发布单向导（APP 形态）：三步表单 + 确认摘要（原型不写入 store）。 */
export default function CreateWizardScreen({ onBack }: { onBack: () => void }) {
  const apps = useStore((s) => s.state.apps);
  const releaseCount = useStore((s) => s.state.releases.length);
  const [step, setStep] = useState(0);
  const [appId, setAppId] = useState(apps[0]?.id ?? '');
  const [title, setTitle] = useState('');
  const [version, setVersion] = useState('');
  const [env, setEnv] = useState<(typeof ENVS)[number]>('dev');
  const [owner, setOwner] = useState('');
  const [flash, showFlash] = useFlash();
  const [done, setDone] = useState(false);

  const app = apps.find((a) => a.id === appId);
  const stepValid =
    (step === 0 && !!appId) ||
    (step === 1 && title.trim() && /^\d+\.\d+\.\d+/.test(version) && owner.trim()) ||
    step === 2;

  return (
    <ProtoShell
      title="新建发布单向导"
      domain="发布工程域 R2"
      desc={`步骤 ${done ? 4 : step + 1}/4：${done ? '完成' : STEPS[step]}`}
      onBack={onBack}
      flash={flash}
    >
      {done ? (
        <Section title="发布单已创建（原型演示）">
          <Text style={{ color: palette.text, fontSize: 13, lineHeight: 20 }}>
            {app?.name} · v{version} → {envLabel(env)}；正式版将写入 store 并进入审批链。
          </Text>
          <Button label="再创建一个" variant="ghost" small onPress={() => { setDone(false); setStep(0); setVersion(''); setTitle(''); setOwner(''); }} />
        </Section>
      ) : (
        <>
          {step === 0 ? (
            <Section title="① 选择目标应用">
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                {apps.map((a) => (
                  <Chip key={a.id} label={`${a.name}（${platformShort(a.platform)}）`} active={a.id === appId} onPress={() => setAppId(a.id)} />
                ))}
              </View>
            </Section>
          ) : step === 1 ? (
            <Section title="② 发布信息">
              <TextInput value={title} onChangeText={setTitle} placeholder="发布标题" placeholderTextColor={palette.muted} accessibilityLabel="发布标题" style={styles.input} />
              <TextInput value={version} onChangeText={setVersion} placeholder="版本号（如 1.2.3）" placeholderTextColor={palette.muted} autoCapitalize="none" accessibilityLabel="版本号" style={styles.input} />
              <TextInput value={owner} onChangeText={setOwner} placeholder="负责人" placeholderTextColor={palette.muted} accessibilityLabel="负责人" style={styles.input} />
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
                {ENVS.map((e) => (
                  <Chip key={e} label={envLabel(e)} active={env === e} onPress={() => setEnv(e)} />
                ))}
              </View>
              <MonoText color={palette.muted}>顺位优先级将取 {releaseCount + 1}（可后续调整）</MonoText>
            </Section>
          ) : (
            <Section title="③ 确认创建">
              <View style={{ gap: 6 }}>
                <Tag label={`${app?.name} · ${app ? platformShort(app.platform) : ''}`} color={TONE_COLOR.info} />
                <MonoText color={palette.text}>{title}</MonoText>
                <MonoText color={palette.text2}>{`v${version} → ${envLabel(env)} · ${owner}`}</MonoText>
              </View>
              <Muted style={{ fontSize: 11, marginTop: 6 }}>提交后按 R1 就绪度检查核验。</Muted>
            </Section>
          )}

          <View style={{ flexDirection: 'row', gap: 10 }}>
            <Button label="上一步" variant="ghost" disabled={step === 0} onPress={() => setStep(step - 1)} />
            {step < 2 ? (
              <Button label="下一步" disabled={!stepValid} onPress={() => setStep(step + 1)} />
            ) : (
              <Button label="创建发布单" onPress={() => { setDone(true); showFlash('原型演示：创建仅本地生效'); }} />
            )}
          </View>
        </>
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
    fontSize: 13,
    color: palette.text,
    marginBottom: 10,
  },
};
