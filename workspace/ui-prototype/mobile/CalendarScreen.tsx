import { useMemo, useState } from 'react';
import { Text, View } from 'react-native';
import { findConflicts, upcomingSchedule, WINDOW_LABEL, type DayWindow, type ScheduleItem } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const WINDOWS: DayWindow[] = ['morning', 'afternoon', 'night'];

/** A4 发布日历（APP 形态）：按日期分组的排期列表 + 冲突提示 + 窗口调整。 */
export default function CalendarScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [items, setItems] = useState<ScheduleItem[] | null>(null);
  const [flash, showFlash] = useFlash();

  const schedule = items ?? upcomingSchedule(state, Date.now());
  const conflicts = useMemo(() => findConflicts(schedule), [schedule]);
  const conflictIds = new Set(conflicts.flat().map((x) => x.releaseId));

  const byDate = useMemo(() => {
    const map = new Map<string, ScheduleItem[]>();
    for (const item of schedule) map.set(item.date, [...(map.get(item.date) ?? []), item]);
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [schedule]);

  const move = (releaseId: string, window: DayWindow) => {
    setItems(schedule.map((x) => (x.releaseId === releaseId ? { ...x, window } : x)));
    showFlash(`原型演示：${releaseId} 已调整到 ${WINDOW_LABEL[window]}`);
  };

  return (
    <ProtoShell
      title="发布日历 / 排期"
      domain="发布单域 A4"
      desc="按日期分组查看发布窗口；同日同环境冲突标红。"
      onBack={onBack}
      flash={flash}
    >
      {conflicts.length > 0 ? (
        <View style={{ borderWidth: 1, borderColor: `${TONE_COLOR.danger}66`, backgroundColor: `${TONE_COLOR.danger}1f`, borderRadius: 12, padding: 10 }}>
          <Text style={{ color: TONE_COLOR.danger, fontSize: 12.5, fontWeight: '700' }}>
            检测到 {conflicts.length} 组排期冲突
          </Text>
          <MonoText color={palette.text2}>
            {conflicts.map((g) => `${g[0].date} ${g[0].env}：${g.map((x) => x.releaseId).join('、')}`).join('\n')}
          </MonoText>
        </View>
      ) : null}

      {byDate.map(([date, list]) => (
        <Section key={date} title={date.slice(5).replace('-', '/')}>
          <View style={{ gap: 8 }}>
            {list.map((x) => (
              <View key={x.releaseId} style={[styles.row, conflictIds.has(x.releaseId) && { borderColor: `${TONE_COLOR.danger}66` }]}>
                <Tag label={x.env} color={conflictIds.has(x.releaseId) ? TONE_COLOR.danger : TONE_COLOR.info} />
                <Text style={{ color: palette.text, fontSize: 12.5, flex: 1 }} numberOfLines={1}>
                  {x.title}
                </Text>
                <View style={{ flexDirection: 'row', gap: 6 }}>
                  {WINDOWS.map((w) => (
                    <Chip key={w} label={WINDOW_LABEL[w].slice(0, 2)} active={x.window === w} onPress={() => move(x.releaseId, w)} />
                  ))}
                </View>
              </View>
            ))}
            {list.length === 0 ? <Muted>空闲窗口</Muted> : null}
          </View>
        </Section>
      ))}
      <Muted style={{ fontSize: 11 }}>冲突口径（演示）：同一日期同一环境存在多条发布。</Muted>
    </ProtoShell>
  );
}

const styles = {
  row: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: 8,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 10,
    backgroundColor: palette.elevated,
  },
};
