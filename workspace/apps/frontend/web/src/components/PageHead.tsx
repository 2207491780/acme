import type { ReactNode } from 'react';
import { Typography } from 'antd';

const { Title } = Typography;

export function PageHead({
  eyebrow,
  title,
  sub,
  extra,
}: {
  eyebrow: string;
  title: string;
  sub?: string;
  extra?: ReactNode;
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 18, marginBottom: 22, flexWrap: 'wrap' }}>
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <Title level={2} style={{ margin: 0, letterSpacing: '-0.6px' }}>{title}</Title>
        {sub && <p className="page-sub">{sub}</p>}
      </div>
      {extra}
    </div>
  );
}