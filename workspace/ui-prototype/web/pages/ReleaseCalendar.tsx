import { useMemo, useState } from 'react';
import { App, Alert, DatePicker, Select, Space, Tag, Typography } from 'antd';
import { upcomingSchedule, WINDOW_LABEL, findConflicts, type DayWindow, type ScheduleItem } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;
const WINDOWS: DayWindow[] = ['morning', 'afternoon', 'night'];

/** A4 发布日历/排期：14 天窗口 × 三个发布窗口，冲突检测高亮（原型仅本地生效）。 */
export default function ReleaseCalendar() {
  const { message } = App.useApp();
  const state = useStore((s) => s.state);
  const [items, setItems] = useState<ScheduleItem[] | null>(null);
  const [filterDate, setFilterDate] = useState<string | null>(null);

  const scheduleAll = items ?? upcomingSchedule(state, Date.now());
  const schedule = filterDate ? scheduleAll.filter((x) => x.date === filterDate) : scheduleAll;
  const conflicts = useMemo(() => findConflicts(schedule), [schedule]);
  const conflictIds = new Set(conflicts.flat().map((x) => x.releaseId));

  const byDate = useMemo(() => {
    const map = new Map<string, ScheduleItem[]>();
    for (const item of schedule) map.set(item.date, [...(map.get(item.date) ?? []), item]);
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [schedule]);

  const move = (releaseId: string, window: DayWindow) => {
    setItems(schedule.map((x) => (x.releaseId === releaseId ? { ...x, window } : x)));
    message.info('原型演示：已调整发布窗口（仅本地生效）');
  };

  return (
    <PageShell
      title="发布日历 / 排期"
      domain="发布单域 A4"
      desc="按日期 × 发布窗口查看排期，同日同环境自动标红冲突；窗口可现场调整用于推演。"
    >
      {conflicts.length > 0 && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message={`检测到 ${conflicts.length} 组排期冲突`}
          description={conflicts
            .map((g) => `${g[0].date} · ${g[0].env}：${g.map((x) => x.releaseId).join(' 与 ')}`)
            .join('；')}
        />
      )}

      <Space size={10} style={{ marginBottom: 14 }} wrap>
        <DatePicker
          placeholder="按日期过滤（可清空）"
          onChange={(d) => setFilterDate(d ? d.format('YYYY-MM-DD') : null)}
          allowClear
          aria-label="按日期过滤排期"
        />
        {filterDate && (
          <Tag color="processing" onClose={() => setFilterDate(null)} closable style={{ cursor: 'pointer' }}>
            仅显示 {filterDate}
          </Tag>
        )}
      </Space>


      {byDate.length === 0 ? (
        <EmptyState title="暂无排期" desc="进行中或草稿的发布单会自动进入排期。" />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {byDate.map(([date, list]) => (
            <Section
              key={date}
              title={
                <span>
                  {date.slice(5).replace('-', '/')}（{new Date(`${date}T00:00:00`).getDay() === 0 || new Date(`${date}T00:00:00`).getDay() === 6 ? '周末' : '工作日'}）
                </span>
              }
            >
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
                {WINDOWS.map((w) => {
                  const inWindow = list.filter((x) => x.window === w);
                  return (
                    <div
                      key={w}
                      style={{
                        borderRadius: 12,
                        border: '1px solid var(--border)',
                        background: 'var(--surface-2)',
                        padding: 10,
                        minHeight: 72,
                      }}
                    >
                      <Text type="secondary" style={{ fontSize: 11 }}>{WINDOW_LABEL[w]}</Text>
                      {inWindow.length === 0 ? (
                        <div style={{ color: 'var(--muted)', fontSize: 12, padding: '8px 0' }}>空闲</div>
                      ) : (
                        inWindow.map((x) => (
                          <div key={x.releaseId} style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
                            <Tag color={conflictIds.has(x.releaseId) ? 'error' : 'processing'} style={{ marginInlineEnd: 0 }}>
                              {x.env}
                            </Tag>
                            <Text style={{ fontSize: 12, flex: 1 }} ellipsis>{x.title}</Text>
                            <Select
                              size="small"
                              variant="borderless"
                              value={x.window}
                              onChange={(v) => move(x.releaseId, v)}
                              aria-label={`调整 ${x.releaseId} 的发布窗口`}
                              options={WINDOWS.map((item) => ({ value: item, label: WINDOW_LABEL[item] }))}
                              popupMatchSelectWidth={false}
                              style={{ width: 76 }}
                            />
                          </div>
                        ))
                      )}
                    </div>
                  );
                })}
              </div>
            </Section>
          ))}
        </div>
      )}
      <HintText style={{ marginTop: 12 }}>
        冲突口径（原型简化）：同一日期同一环境存在多条发布即冲突。成熟化时考虑窗口时长重叠与人工冻结期。
      </HintText>
    </PageShell>
  );
}
