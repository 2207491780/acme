import { useState } from 'react';
import { Text, View } from 'react-native';
import { fmtDate, INTEGRATION_META, integrationCards, type IntegrationCard, type Tone } from '@acme/core';
import { Button, Muted, Tag } from '../../components/ui';
import { MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

/** G1 集成中心（APP 形态）：外部系统连接卡 + 连通性测试（凭证脱敏）。 */
export default function IntegrationScreen({ onBack }: { onBack: () => void }) {
  const [testing, setTesting] = useState<string | null>(null);
  const [flash, showFlash] = useFlash();
  const cards = integrationCards(Date.now());

  const test = (card: IntegrationCard) => {
    setTesting(card.id);
    setTimeout(() => {
      setTesting(null);
      showFlash(card.status === 'error' ? '连通性测试失败（演示：模拟异常）' : '连通性测试通过（演示）');
    }, 700);
  };

  return (
    <ProtoShell
      title="集成中心"
      domain="集成域 G1"
      desc="外部系统与通知渠道的连接状态；端点仅脱敏展示。"
      onBack={onBack}
      flash={flash}
    >
      {cards.map((card) => {
        const meta = INTEGRATION_META[card.status];
        return (
          <Section key={card.id} title={`${card.name} · ${card.category}`}>
            <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 6, flexWrap: 'wrap' }}>
              <Tag label={meta.label} color={TONE_COLOR[meta.tone as Tone]} />
              <MonoText>{card.category}</MonoText>
            </View>
            <Text style={{ color: palette.text2, fontSize: 12, fontFamily: undefined }}>{card.endpoint}</Text>
            <Text style={{ color: palette.text2, fontSize: 12.5, marginTop: 4 }}>{card.note}</Text>
            <MonoText color={palette.muted}>最近同步：{card.lastSync ? fmtDate(card.lastSync) : '—'}</MonoText>
            <Button label={testing === card.id ? '测试中…' : '测试连通'} variant="ghost" small onPress={() => test(card)} />
          </Section>
        );
      })}
      <Muted style={{ fontSize: 11 }}>异常连接（监控）会阻塞 S1 事故自动创建与 D2 通知推送；凭证按 .ai/env 纪律只存环境侧。</Muted>
    </ProtoShell>
  );
}
