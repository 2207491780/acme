import { Button, DatePicker, Form, Input, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { auditApi, formatDate, useCrud, type AdminAuditEventView } from '@acme/core';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

/** 已提交查询过滤（range 在提交时序列化为 from/to ISO 串，适配 useCrud 字符串筛选形状）。
 *  用 type 而非 interface：类型别名才满足 CrudFilters 的 Record<string, string | undefined> 约束。 */
type AuditFilters = {
  operatorId?: string;
  action?: string;
  traceId?: string;
  from?: string;
  to?: string;
};

interface AuditFormValues {
  operatorId?: string;
  action?: string;
  traceId?: string;
  range?: [Dayjs, Dayjs];
}

/** 审计事件查询（p3-audit，权限点 infra:audit:list）：
 *  全租户视图需 infra:audit:list:all（服务端两级数据权限），否则仅本租户事件。
 *  迁移 CRUD 范本（REQ-20261114-120101）：useCrud（表单筛选走 filters，提交重置第 1 页）
 *  + CrudTable（filterPanel 保持原查询表单布局；查询型页面无新建/删除）。 */
export function AuditPage() {
  const [form] = Form.useForm<AuditFormValues>();

  const crud = useCrud<AdminAuditEventView, AuditFilters>({
    fetchPage: ({ page, size, filters }) =>
      auditApi.events(page, size, {
        operatorId: filters?.operatorId,
        action: filters?.action,
        traceId: filters?.traceId,
        from: filters?.from,
        to: filters?.to,
      }),
    filters: { operatorId: undefined, action: undefined, traceId: undefined, from: undefined, to: undefined },
    pageSize: 20,
    errorMessage: '审计事件加载失败',
  });

  const columns: ColumnsType<AdminAuditEventView> = [
    { title: '时间', dataIndex: 'occurredAt', key: 'occurredAt', width: 170, render: (v: string) => formatDate(v) },
    { title: '操作者', dataIndex: 'operatorId', key: 'operatorId', width: 140, render: (v: string) => <Text code>{v}</Text> },
    { title: '端', dataIndex: 'channel', key: 'channel', width: 70, render: (v: string) => <Tag>{v}</Tag> },
    { title: '动作', dataIndex: 'action', key: 'action', width: 180, render: (v: string) => <Text code>{v}</Text> },
    { title: '对象', key: 'target', width: 200, render: (_, record) => (record.targetId ? `${record.targetType}:${record.targetId}` : record.targetType) },
    { title: '内容', dataIndex: 'content', key: 'content', ellipsis: true },
    { title: 'Trace', dataIndex: 'traceId', key: 'traceId', width: 140, ellipsis: true, render: (v: string) => (v ? <Text code style={{ fontSize: 11 }}>{v.slice(0, 12)}…</Text> : '—') },
  ];

  return (
    <CrudTable<AdminAuditEventView, AuditFilters>
      crud={crud}
      title="审计事件"
      columns={columns}
      searchable={false}
      reloadAriaLabel="刷新审计事件"
      totalText={(t) => `共 ${t} 条`}
      filterPanel={
        <Form
          form={form}
          layout="inline"
          style={{ marginBottom: 16, flexWrap: 'wrap', rowGap: 8 }}
          onFinish={(values: AuditFormValues) => {
            // 提交即整组过滤替换并重置到第 1 页（多次 setFilter 在同一事件批内合并为一次拉取）
            crud.setFilter('operatorId', values.operatorId || undefined);
            crud.setFilter('action', values.action || undefined);
            crud.setFilter('traceId', values.traceId || undefined);
            crud.setFilter('from', values.range?.[0]?.toISOString());
            crud.setFilter('to', values.range?.[1]?.toISOString());
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
                crud.resetFilters();
              }}
            >
              重置
            </Button>
          </Space>
        </Form>
      }
    />
  );
}
