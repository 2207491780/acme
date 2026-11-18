import type { ReactNode } from 'react';
import { Alert, Button, Tag } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { PageHead } from '../../components/PageHead';

/** 原型页统一外壳：「原型」徽标 + 返回索引 + 领域标注，保证原型不会被误认成正式功能。 */
export function PageShell({
  title,
  domain,
  desc,
  extra,
  children,
}: {
  title: string;
  domain: string;
  desc: string;
  extra?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 14 }}>
        <Link to="/prototype">
          <Button size="small" icon={<ArrowLeftOutlined />}>
            原型索引
          </Button>
        </Link>
        <Tag color="warning">原型预览</Tag>
        <Tag>{domain}</Tag>
        <span style={{ color: 'var(--muted)', fontSize: 12 }}>演示数据 · 不落库 · 成熟化后迁入正式页面</span>
      </div>
      <PageHead eyebrow={domain} title={title} sub={desc} extra={extra} />
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="原型演示：交互仅本地生效，不会写入正式数据。"
      />
      {children}
    </div>
  );
}
