import type { ReactNode } from 'react';
import { Button, Card, Empty, Result, Skeleton } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';

/** F1 全局状态规范（原型沉淀层）：加载 / 空 / 错误三类态的统一呈现。
 *  成熟化时随组件一起迁入正式 components/。 */

export function SkeletonBlock({ rows = 3, height = 120 }: { rows?: number; height?: number }) {
  return (
    <div style={{ minHeight: height }}>
      <Skeleton active paragraph={{ rows }} />
    </div>
  );
}

export function SkeletonCard({ rows = 3 }: { rows?: number }) {
  return (
    <Card>
      <Skeleton active avatar paragraph={{ rows }} />
    </Card>
  );
}

export function EmptyState({
  title = '暂无数据',
  desc,
  action,
}: {
  title?: string;
  desc?: string;
  action?: ReactNode;
}) {
  return (
    <Empty
      description={
        <span>
          <strong style={{ display: 'block', marginBottom: 4 }}>{title}</strong>
          {desc && <span style={{ fontSize: 12, color: 'var(--muted)' }}>{desc}</span>}
        </span>
      }
      style={{ padding: '32px 0' }}
    >
      {action}
    </Empty>
  );
}

export function ErrorState({
  code = 500,
  title,
  desc,
  onRetry,
}: {
  code?: 403 | 404 | 500;
  title?: string;
  desc?: string;
  onRetry?: () => void;
}) {
  const fallback: Record<NonNullable<typeof code>, { title: string; desc: string }> = {
    403: { title: '无权访问', desc: '当前角色无权查看该内容，请联系负责人开通权限。' },
    404: { title: '页面不存在', desc: '链接可能已失效，或该原型尚未实现。' },
    500: { title: '加载失败', desc: '演示数据管道异常，可尝试重试。' },
  };
  const t = fallback[code];
  return (
    <Result
      status={code}
      title={title ?? t.title}
      subTitle={desc ?? t.desc}
      extra={
        onRetry && (
          <Button icon={<ReloadOutlined />} onClick={onRetry}>
            重试
          </Button>
        )
      }
    />
  );
}
