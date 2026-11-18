import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { App, Button, Checkbox, Select, Space, Tag } from 'antd';
import { RedoOutlined } from '@ant-design/icons';
import { buildLog, type LogLine } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, LogView, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const STAGES: LogLine['stage'][] = ['install', 'build', 'test', 'package', 'deploy'];
const LEVELS: Array<{ value: LogLine['level']; label: string }> = [
  { value: 'info', label: '信息' },
  { value: 'cmd', label: '命令' },
  { value: 'warn', label: '警告' },
  { value: 'error', label: '错误' },
];

/** A2 构建日志页：分阶段/分级过滤、重新生成演示（原型仅本地生效）。 */
export default function BuildLog() {
  const { message } = App.useApp();
  const releases = useStore((s) => s.state.releases);
  const [params] = useSearchParams();
  const [releaseId, setReleaseId] = useState(params.get('release') ?? releases[0]?.id ?? '');
  const [stage, setStage] = useState<'all' | LogLine['stage']>('all');
  const [levels, setLevels] = useState<LogLine['level'][]>(['info', 'cmd', 'warn', 'error']);
  const [runKey, setRunKey] = useState(0);

  const release = releases.find((r) => r.id === releaseId);
  const allLines = useMemo(() => (release ? buildLog(release, String(runKey)) : []), [release, runKey]);
  const lines = allLines.filter(
    (l) => (stage === 'all' || l.stage === stage) && levels.includes(l.level),
  );

  return (
    <PageShell
      title="构建日志"
      domain="发布单域 A2"
      desc="按阶段 / 级别过滤的实时日志视图原型；失败单自动注入错误与流水线中止样本。"
      extra={
        <Space>
          <Select
            style={{ width: 260 }}
            value={releaseId || undefined}
            onChange={setReleaseId}
            placeholder="选择发布单"
            aria-label="选择发布单"
            options={releases.map((r) => ({ value: r.id, label: `${r.id} · ${r.title}` }))}
          />
          <Button
            icon={<RedoOutlined />}
            onClick={() => {
              setRunKey((k) => k + 1);
              message.info('原型演示：已重新生成日志（仅本地生效）');
            }}
          >
            重新生成
          </Button>
        </Space>
      }
    >
      {!release ? (
        <EmptyState title="请选择发布单" desc="选择一个发布单查看其构建日志。" />
      ) : (
        <>
          <Section
            title={
              <Space wrap size={6}>
                <span>日志过滤</span>
                <Select
                  size="small"
                  style={{ width: 120 }}
                  value={stage}
                  onChange={setStage}
                  aria-label="按阶段过滤"
                  options={[{ value: 'all', label: '全部阶段' }, ...STAGES.map((s) => ({ value: s, label: s }))]}
                />
                <Checkbox.Group
                  options={LEVELS}
                  value={levels}
                  onChange={(v) => setLevels(v as LogLine['level'][])}
                  aria-label="按级别过滤"
                />
                <Tag color={release.status === 'failed' ? 'error' : 'processing'}>
                  {release.id} · v{release.version}
                </Tag>
              </Space>
            }
          >
            <LogView lines={lines} />
            <HintText style={{ marginTop: 10 }}>
              共 {allLines.length} 行，过滤后 {lines.length} 行；时间轴从流水线启动起算。
              成熟化时替换为真实日志流（SSE/分段拉取）。
            </HintText>
          </Section>
          {release.status === 'failed' && lines.some((l) => l.level === 'error') && (
            <Section title="失败摘要" style={{ marginTop: 16 }}>
              <HintText>
                错误集中在 test 阶段：快照回归不一致。处理建议：修复小屏手势安全区 → 重新构建（对应正式页「重试构建」动作）。
              </HintText>
            </Section>
          )}
        </>
      )}
    </PageShell>
  );
}
