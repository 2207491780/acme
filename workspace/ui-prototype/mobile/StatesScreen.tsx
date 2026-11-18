import { useEffect, useRef, useState } from 'react';
import { Animated, View } from 'react-native';
import { useStore } from '../../store/useStore';
import { Button, Card, Muted, Tag } from '../../components/ui';
import { Kpi, KpiRow, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

type Mode = 'loading' | 'empty' | 'error' | 'ready';

function SkeletonBlock({ boxes = 3 }: { boxes?: number }) {
  const opacity = useRef(new Animated.Value(0.4)).current;
  useEffect(() => {
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(opacity, { toValue: 1, duration: 700, useNativeDriver: true }),
      Animated.timing(opacity, { toValue: 0.4, duration: 700, useNativeDriver: true }),
    ]));
    loop.start();
    return () => loop.stop();
  }, [opacity]);
  return (
    <View style={{ gap: 8, paddingVertical: 6 }}>
      {Array.from({ length: boxes }).map((_, i) => (
        <Animated.View key={i} style={{ height: 14, borderRadius: 7, backgroundColor: palette.elevated, opacity, width: `${100 - i * 12}%` }} />
      ))}
    </View>
  );
}

/** F1 全局状态规范（APP 形态）：加载 / 空 / 错误 / 就绪四态统一规格。 */
export default function StatesScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [mode, setMode] = useState<Mode>('loading');
  const [flash, showFlash] = useFlash();

  return (
    <ProtoShell
      title="全局状态规范"
      domain="横向专项 F1"
      desc="空态 / 加载态 / 错误态 / 就绪态的统一呈现；成熟化时迁入正式 ui.tsx。"
      onBack={onBack}
      flash={flash}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 4 }}>
        {(['loading', 'empty', 'error', 'ready'] as const).map((m) => (
          <Button key={m} label={{ loading: '加载中', empty: '空数据', error: '错误', ready: '就绪' }[m]} variant={mode === m ? 'primary' : 'ghost'} small onPress={() => setMode(m)} />
        ))}
      </View>

      <Card>
        {mode === 'loading' && <SkeletonBlock boxes={4} />}
        {mode === 'empty' && (
          <View style={{ alignItems: 'center', paddingVertical: 20, gap: 8 }}>
            <Tag label="暂无发布单" color={TONE_COLOR.muted} />
            <Muted>调整筛选，或新建一个发布单。</Muted>
            <Button label="新建发布单（原型）" onPress={() => showFlash('原型演示：仅本地生效')} />
          </View>
        )}
        {mode === 'error' && (
          <View style={{ alignItems: 'center', paddingVertical: 20, gap: 8 }}>
            <Tag label="加载失败" color={TONE_COLOR.danger} />
            <Muted>演示数据管道异常，可尝试重试。</Muted>
            <Button label="重试" variant="ghost" onPress={() => showFlash('原型演示：重试成功')} />
          </View>
        )}
        {mode === 'ready' && (
          <View style={{ gap: 10 }}>
            <KpiRow>
              <Kpi label="进行中" value={releases.filter((r) => ['building', 'testing', 'approved'].includes(r.status)).length} tone="info" />
              <Kpi label="已发布" value={releases.filter((r) => r.status === 'published').length} tone="success" />
            </KpiRow>
            <Muted style={{ fontSize: 11 }}>就绪态为常规数据布局（Kpi / Section 区块）。</Muted>
          </View>
        )}
      </Card>

      <Section title="使用规格（实施清单）">
        <Muted style={{ fontSize: 12, lineHeight: 20 }}>
          · 加载态：骨架屏（Animated 闪烁），禁止白屏静默{'\n'}
          · 空态：必须给出下一步动作按钮{'\n'}
          · 错误态：可重试，重试必须真的触发重新加载{'\n'}
          · 无障碍：所有状态容器带 accessibilityLabel
        </Muted>
      </Section>
    </ProtoShell>
  );
}
