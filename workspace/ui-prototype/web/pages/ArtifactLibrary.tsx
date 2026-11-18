import { useMemo, useState } from 'react';
import { App, Button, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DownloadOutlined, FileZipOutlined, SearchOutlined } from '@ant-design/icons';
import { Input } from 'antd';
import { artifactInventory, fmtFull, statusLabel, statusTone, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;

/** R3 制品库：全量产物聚合（跨发布单）、按应用/环境检索、下载为演示口径。 */
export default function ArtifactLibrary() {
  const { message } = App.useApp();
  const state = useStore((s) => s.state);
  const rows = useMemo(() => artifactInventory(state), [state]);
  const [appId, setAppId] = useState('all');
  const [env, setEnv] = useState('all');
  const [keyword, setKeyword] = useState('');

  const filtered = rows.filter(
    (r) =>
      (appId === 'all' || r.appId === appId) &&
      (env === 'all' || r.env === env) &&
      (!keyword.trim() || `${r.name}${r.releaseId}${r.version}`.toLowerCase().includes(keyword.trim().toLowerCase())),
  );

  const columns: ColumnsType<(typeof rows)[number]> = [
    {
      title: '产物',
      key: 'name',
      render: (_, r) => (
        <Space size={6}>
          <FileZipOutlined style={{ color: 'var(--accent)' }} />
          <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{r.name}</Text>
        </Space>
      ),
    },
    { title: '版本', dataIndex: 'version', width: 90, render: (v: string) => <Tag color="processing">v{v}</Tag> },
    { title: '应用', dataIndex: 'appId', width: 110, render: (id: string) => state.apps.find((a) => a.id === id)?.name ?? id },
    { title: '环境', dataIndex: 'env', width: 80, render: (e: string) => <Tag>{e}</Tag> },
    { title: '大小', dataIndex: 'size', width: 90, render: (s: string) => <Text style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{s}</Text> },
    { title: '来源发布单', key: 'release', render: (_, r) => (
      <Space size={4}>
        <Text style={{ fontFamily: 'var(--mono)', fontSize: 11 }}>{r.releaseId}</Text>
        <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
      </Space>
    ) },
    { title: '更新时间', dataIndex: 'createdAt', width: 140, render: (v: string) => <Text type="secondary" style={{ fontSize: 11 }}>{fmtFull(v)}</Text> },
    {
      title: '',
      key: 'dl',
      width: 90,
      render: (_, r) => (
        <Button size="small" icon={<DownloadOutlined />} aria-label={`下载产物 ${r.name}`} onClick={() => message.info('原型演示：下载仅本地模拟')}>
          下载
        </Button>
      ),
    },
  ];

  const totalSizeMb = rows.length; // 演示口径：条目数代替容量

  return (
    <PageShell
      title="制品库"
      domain="发布工程域 R3"
      desc="跨发布单的产物聚合视图：检索历史版本产物、追溯来源发布单；保留策略成熟化后接入。"
    >
      <KpiRow>
        <Kpi label="产物总数" value={rows.length} tone="info" />
        <Kpi label="涉及应用" value={new Set(rows.map((r) => r.appId)).size} tone="violet" />
        <Kpi label="已发布产物" value={rows.filter((r) => r.status === 'published').length} tone="success" />
        <Kpi label="当前筛选" value={filtered.length} tone="muted" hint={`共 ${totalSizeMb} 条记录`} />
      </KpiRow>

      <Section
        title={
          <Space size={8} wrap>
            <Select size="small" style={{ width: 130 }} value={appId} onChange={setAppId} aria-label="按应用过滤"
              options={[{ value: 'all', label: '全部应用' }, ...state.apps.map((a) => ({ value: a.id, label: a.name }))]} />
            <Select size="small" style={{ width: 110 }} value={env} onChange={setEnv} aria-label="按环境过滤"
              options={[{ value: 'all', label: '全部环境' }, { value: 'dev', label: 'dev' }, { value: 'test', label: 'test' }, { value: 'prod', label: 'prod' }]} />
            <Input size="small" style={{ width: 200 }} prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />} placeholder="搜索产物名 / 版本" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear aria-label="搜索产物" />
            <Tag>{filtered.length} 条</Tag>
          </Space>
        }
      >
        <Table size="small" rowKey={(r) => `${r.releaseId}-${r.name}`} columns={columns} dataSource={filtered} pagination={{ pageSize: 8 }} aria-label="制品清单"
          locale={{ emptyText: '没有匹配的产物。' }} />
      </Section>
      <HintText style={{ marginTop: 12 }}>
        保留策略（如 prod 保留 90 天 / test 30 天）成熟化时接入；当前为全量演示数据。
      </HintText>
    </PageShell>
  );
}
