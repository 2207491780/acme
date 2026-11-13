import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, DatePicker, Form, Input, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { ApiError } from '../api/client';
import { auditApi, type AdminAuditEventView } from '../api/audit';

const { Text } = Typography;

interface AuditFilters {
  operatorId?: string;
  action?: string;
  traceId?: string;
  range?: [Dayjs, Dayjs];
}

/** 审计事件查询（p3-audit，权限点 infra:audit:list）：
 *  全租户视图需 infra:audit:list:all（服务端两级数据权限），否则仅本租户事件。 */
export function AuditPage() {
  const [records, setRecords] = useState<AdminAuditEventView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size] = useState(20);
  const [filters, setFilters] = useState<AuditFilters>({});
  const [queryVersion, setQueryVersion] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [form] = Form.useForm<AuditFilters>();

  const reload = useCallback((targetPage = page) => {
    setLoading(true);
    setLoadError(null);
    auditApi.events(targetPage, size, {
      operatorId: filters.operatorId,
      action: filters.action,
      traceId: filters.traceId,
      from: filters.range?.[0]?.toISOString(),
      to: filters.range?.[1]?.toISOString(),
    })
      .then((result) => {
        setRecords(result.records);
        setTotal(Number(result.total));
      })
      .catch((ex: unknown) => {
        setLoadError(ex instanceof ApiError ? ex.message : '审计事件加载失败');
      })
      .finally(() => setLoading(false));
  }, [page, size, filters]);

  useEffect(() => {
    reload(page);
  }, [page, queryVersion]);

  const columns: ColumnsType<AdminAuditEventView> = [
    { title: '时间', dataIndex: 'occurredAt', key: 'occurredAt', width: 170, render: (v: string) => new Date(v).toLocaleString() },
    { title: '操作者', dataIndex: 'operatorId', key: 'operatorId', width: 140, render: (v: string) => <Text code>{v}</Text> },
    { title: '端', dataIndex: 'channel', key: 'channel', width: 70, render: (v: string) => <Tag>{v}</Tag> },
    { title: '动作', dataIndex: 'action', key: 'action', width: 180, render: (v: string) => <Text code>{v}</Text> },
    { title: '对象', key: 'target', width: 200, render: (_, record) => (record.targetId ? `${record.targetType}:${record.targetId}` : record.targetType) },
    { title: '内容', dataIndex: 'content', key: 'content', ellipsis: true },
    { title: 'Trace', dataIndex: 'traceId', key: 'traceId', width: 140, ellipsis: true, render: (v: string) => (v ? <Text code style={{ fontSize: 11 }}>{v.slice(0, 12)}…</Text> : '—') },
  ];

  return (
    <Card title="审计事件" extra={<Button icon={<ReloadOutlined />} onClick={() => reload(page)} aria-label="刷新审计事件" />}>
      <Form
        form={form}
        layout="inline"
        style={{ marginBottom: 16, flexWrap: 'wrap', rowGap: 8 }}
        onFinish={(values: AuditFilters) => {
          setFilters(values);
          setPage(1);
          setQueryVersion((v) => v + 1);
        }}
      >
        <Form.Item name="operatorId">
          <Input placeholder="操作者 ID" allowClear style={{ width: 160 }} />
        </Form.Item>
        <Form.Item name="action">
          <Input placeholder="动作（如 release.create）" allowClear style={{ width: 200 }} />
        </Form.Item>
        <Form.Item name="traceId">
          <Input placeholder="Trace ID" allowClear style={{ width: 180 }} />
        </Form.Item>
        <Form.Item name="range">
          <DatePicker.RangePicker showTime style={{ width: 340 }} />
        </Form.Item>
        <Space size={4}>
          <Button type="primary" htmlType="submit">查询</Button>
          <Button
            onClick={() => {
              form.resetFields();
              setFilters({});
              setPage(1);
              setQueryVersion((v) => v + 1);
            }}
          >
            重置
          </Button>
        </Space>
      </Form>
      {loadError && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message={loadError}
          action={<Button size="small" onClick={() => reload(page)}>重试</Button>} />
      )}
      <Table<AdminAuditEventView>
        rowKey="id"
        columns={columns}
        dataSource={records}
        loading={loading}
        pagination={{ current: page, pageSize: size, total, showTotal: (t) => `共 ${t} 条`, onChange: (p) => setPage(p) }}
      />
    </Card>
  );
}
