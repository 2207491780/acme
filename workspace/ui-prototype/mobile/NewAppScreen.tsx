import { useState } from 'react';
import { TextInput, Text, View } from 'react-native';
import { PLATFORMS, platformLabel, type Platform } from '@acme/core';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const STEP_TITLES = ['选择平台', '仓库与构建', '确认创建', '完成'];

/** E2 新建应用向导（APP 形态）：三步表单 + 确认摘要（仅本地生效）。 */
export default function NewAppScreen({ onBack }: { onBack: () => void }) {
  const [step, setStep] = useState(0);
  const [platform, setPlatform] = useState<Platform | null>(null);
  const [name, setName] = useState('');
  const [repoUrl, setRepoUrl] = useState('');
  const [buildCommand, setBuildCommand] = useState('');
  const [flash, showFlash] = useFlash();
  const [done, setDone] = useState(false);

  const stepValid =
    (step === 0 && !!platform) ||
    (step === 1 && name.trim() && /^https?:\/\/.+/.test(repoUrl) && buildCommand.trim()) ||
    step === 2;

  return (
    <ProtoShell
      title="新建应用向导"
      domain="应用与配置域 E2"
      desc={`步骤 ${done ? 4 : step + 1}/4：${done ? '完成' : STEP_TITLES[step]}`}
      onBack={onBack}
      flash={flash}
    >
      {done ? (
        <Section title="应用已创建（原型演示）">
          <Text style={{ color: palette.text, fontSize: 13, lineHeight: 20 }}>
            正式版将写入应用清单并生成首批发布单模板。
          </Text>
          <Button label="再创建一个" variant="ghost" small onPress={() => { setDone(false); setStep(0); setPlatform(null); }} />
        </Section>
      ) : (
        <>
          {step === 0 ? (
            <Section title="① 选择目标平台">
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                {PLATFORMS.map((p) => (
                  <Chip key={p} label={platformLabel(p)} active={platform === p} onPress={() => setPlatform(p)} />
                ))}
              </View>
              <Muted style={{ fontSize: 11, marginTop: 8 }}>桌面端复用 Web 产物，移动端共享 core。</Muted>
            </Section>
          ) : step === 1 ? (
            <Section title="② 仓库与构建配置">
              <TextInput value={name} onChangeText={setName} placeholder="应用名称" placeholderTextColor={palette.muted} accessibilityLabel="应用名称" style={styles.input} />
              <TextInput value={repoUrl} onChangeText={setRepoUrl} placeholder="仓库地址（https://…）" placeholderTextColor={palette.muted} autoCapitalize="none" accessibilityLabel="仓库地址" style={styles.input} />
              <TextInput value={buildCommand} onChangeText={setBuildCommand} placeholder="构建命令（如 pnpm build）" placeholderTextColor={palette.muted} autoCapitalize="none" accessibilityLabel="构建命令" style={styles.input} />
            </Section>
          ) : (
            <Section title="③ 确认创建摘要">
              <View style={{ gap: 6 }}>
                <Tag label={`平台：${platform ? platformLabel(platform) : '—'}`} color={TONE_COLOR.info} />
                <MonoText color={palette.text}>名称：{name}</MonoText>
                <MonoText color={palette.text2}>仓库：{repoUrl}</MonoText>
                <MonoText color={palette.text2}>构建：{buildCommand}</MonoText>
              </View>
              <Muted style={{ fontSize: 11, marginTop: 6 }}>原型不写入正式数据。</Muted>
            </Section>
          )}

          <View style={{ flexDirection: 'row', gap: 10, marginTop: 4 }}>
            <Button label="上一步" variant="ghost" disabled={step === 0} onPress={() => setStep(step - 1)} />
            {step < 2 ? (
              <Button label="下一步" disabled={!stepValid} onPress={() => setStep(step + 1)} />
            ) : (
              <Button label="创建应用" disabled={!stepValid} onPress={() => { setDone(true); showFlash('原型演示：仅本地生效'); }} />
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
