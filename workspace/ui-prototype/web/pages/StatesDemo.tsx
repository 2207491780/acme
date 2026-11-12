import { useState } from 'react';
import { App, Button, Segmented, Space, Tag } from 'antd';
import { PageShell } from '../shell/PageShell';
import { EmptyState, ErrorState, SkeletonBlock, SkeletonCard } from '../components/states';
import { Kpi, KpiRow, Section, HintText } from '../components/blocks';

type Mode = 'loading' | 'empty' | 'error403' | 'error404' | 'error500' | 'ready';

const MODES: Array<{ value: Mode; label: string }> = [
  { value: 'loading', label: '加载中' },
  { value: 'empty', label: '空数据' },
  { value: 'error403', label: '403' },
  { value: 'error404', label: '404' },
  { value: 'error500', label: '500' },
  { value: 'ready', label: '就绪' },
];

/** F1 全局状态规范：空态 / 加载态 / 错误态的统一规格与演示。 */
export default function StatesDemo() {
  const { message } = App.useApp();
  const [mode, setMode] = useState<Mode>('loading');

  return (
    <PageShell
      title="全局状态规范"
      domain="横向专项 F1"
      desc="空态 / 加载态 / 错误态的统一呈现规格。成熟化后迁入正式 components/，作为所有新页面的地基。"
    >
      <Section title="状态切换预览">
        <Segmented options={MODES} value={mode} onChange={(v) => setMode(v as Mode)} style={{ marginBottom: 16 }} />
        <div style={{ minHeight: 220 }}>
          {mode === 'loading' && <SkeletonCard rows={4} />}
          {mode === 'empty' && (
            <EmptyState
              title="没有匹配的发布单"
              desc="调整筛选条件，或新建一个发布单。"
              action={
                <Button type="primary" onClick={() => message.info('原型演示：仅本地生效')}>
                  新建发布单
                </Button>
              }
            />
          )}
          {mode === 'error403' && <ErrorState code={403} />}
          {mode === 'error404' && <ErrorState code={404} />}
          {mode === 'error500' && <ErrorState code={500} onRetry={() => message.success('原型演示：重试成功')} />}
          {mode === 'ready' && (
            <div>
              <KpiRow>
                <Kpi label="进行中" value={7} tone="info" />
                <Kpi label="待发布" value={2} tone="warning" />
                <Kpi label="今日发布" value={3} tone="success" />
                <Kpi label="失败待处理" value={1} tone="danger" />
              </KpiRow>
              <HintText>就绪态为常规数据卡片布局（复用 Kpi / Section 区块）。</HintText>
            </div>
          )}
        </div>
      </Section>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginTop: 16 }}>
        <Section title="骨架屏变体 · 列表">
          <SkeletonBlock rows={4} />
        </Section>
        <Section title="骨架屏变体 · 卡片">
          <SkeletonCard rows={2} />
        </Section>
      </div>

      <Section title="使用规格（实施清单）" style={{ marginTop: 16 }}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <span><Tag color="info">加载态</Tag>列表页用 <code>SkeletonBlock</code>，卡片页用 <code>SkeletonCard</code>；懒加载路由统一 Spin。</span>
          <span><Tag color="default">空态</Tag>必须给出下一步动作（新建/调整筛选/清空搜索），不允许只有一句话。</span>
          <span><Tag color="error">错误态</Tag>403 权限 / 404 不存在 / 500 可重试；重试按钮必须真的触发重新加载。</span>
          <span><Tag color="warning">无障碍</Tag>Empty/Result 自带语义；骨架屏区域加载完成后焦点交还列表标题。</span>
        </Space>
      </Section>
    </PageShell>
  );
}
