import { useMemo, useRef, useState } from 'react';
import { Animated, PanResponder, RefreshControl, ScrollView, Text, View } from 'react-native';
import { statusLabel, statusTone } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const SWIPE_THRESHOLD = 90;

/** F4 移动端手势深化（APP 形态）：卡片左滑驳回 / 右滑通过、下拉刷新（原生内建能力，零新依赖）。 */
export default function GesturesScreen({ onBack }: { onBack: () => void }) {
  const releases = useStore((s) => s.state.releases);
  const [flash, showFlash] = useFlash();
  const [refreshing, setRefreshing] = useState(false);
  const [handled, setHandled] = useState<string[]>([]);
  const pos = useRef(new Animated.ValueXY({ x: 0, y: 0 })).current;
  const target = releases.find((r) => !handled.includes(r.id)) ?? releases[0];

  const settle = (dir: 1 | -1) => {
    if (!target) return;
    setHandled((cur) => [...cur, target.id]);
    showFlash(`原型演示：${target.id} ${dir === 1 ? '右滑 → 通过' : '← 左滑驳回'}（仅本地生效）`);
    pos.setValue({ x: 0, y: 0 });
  };

  const pan = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_e, g) => Math.abs(g.dx) > 12 && Math.abs(g.dy) < 40,
        onPanResponderMove: (_e, g) => pos.setValue({ x: g.dx, y: 0 }),
        onPanResponderRelease: (_e, g) => {
          if (g.dx > SWIPE_THRESHOLD) settle(1);
          else if (g.dx < -SWIPE_THRESHOLD) settle(-1);
          else Animated.spring(pos, { toValue: { x: 0, y: 0 }, useNativeDriver: true }).start();
        },
      }),
    // settle 依赖最新 target；演示屏可接受闭包重建
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [target?.id],
  );

  return (
    <ProtoShell
      title="手势与交互深化"
      domain="横向专项 F4"
      desc="卡片滑动操作（右滑通过 / 左滑驳回）、下拉刷新、骨架屏——全部用 RN 内建能力实现，零新依赖。"
      onBack={onBack}
      flash={flash}
    >
      <Section title="滑动操作（Release Card）">
        {target ? (
          <Animated.View
            style={[
              {
                transform: [{ translateX: pos.x }],
                borderWidth: 1,
                borderColor: palette.border,
                borderRadius: 14,
                padding: 12,
                backgroundColor: palette.elevated,
              },
            ]}
            {...pan.panHandlers}
          >
            <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 6 }}>
              <Tag label={target.id} color={TONE_COLOR.muted} />
              <Tag label={statusLabel(target.status)} color={TONE_COLOR[statusTone(target.status)]} />
            </View>
            <Text style={{ color: palette.text, fontSize: 14, fontWeight: '700' }}>{target.title}</Text>
            <Muted style={{ fontSize: 11, marginTop: 6 }}>左右拖动这张卡片试试（阈值 ±{SWIPE_THRESHOLD}px）</Muted>
          </Animated.View>
        ) : (
          <Muted>没有可操作的卡片。</Muted>
        )}
        <MonoText color={palette.muted}>已处理 {handled.length} 张；滑动只改本地演示状态。</MonoText>
      </Section>

      <Section title="下拉刷新（RefreshControl）">
        <ScrollView
          style={{ borderWidth: 1, borderColor: palette.border, borderRadius: 12 }}
          contentContainerStyle={{ padding: 16, gap: 6 }}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              tintColor={TONE_COLOR.info}
              onRefresh={() => {
                setRefreshing(true);
                setTimeout(() => {
                  setRefreshing(false);
                  showFlash('原型演示：数据已刷新（仅本地生效）');
                }, 900);
              }}
            />
          }
        >
          <Text style={{ color: palette.text2, fontSize: 12.5 }}>向下拉动本区域触发刷新指示器。</Text>
          <Muted style={{ fontSize: 11 }}>成熟化时接真实数据重载；当前模拟 0.9s 延迟。</Muted>
        </ScrollView>
      </Section>
    </ProtoShell>
  );
}
