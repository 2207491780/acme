import { Button, DatePicker, Form, Input, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { auditApi, formatDate, useCrud, type AdminAuditEventView } from '@acme/core';
import { CrudTable } from '../components/CrudTable';
import { FilterPanel } from '../components/FilterPanel';

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
  const { t } = useTranslation();
  const [form] = Form.useForm<AuditFormValues>();

  const crud = useCrud<AdminAuditEventView, AuditFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      auditApi.events(page, size, {
        operatorId: filters?.operatorId,
        action: filters?.action,
        traceId: filters?.traceId,
        from: filters?.from,
        to: filters?.to,
      }, signal),
    filters: { operatorId: undefined, action: undefined, traceId: undefined, from: undefined, to: undefined },
    pageSize: 20,
    errorMessage: t('audit.errors.loadFailed'),
  });

  const columns: ColumnsType<AdminAuditEventView> = [
    { title: t('audit.columns.occurredAt'), dataIndex: 'occurredAt', key: 'occurredAt', minWidth: 170, fixed: 'left', render: (v: string) => formatDate(v) },
    { title: t('audit.columns.operatorId'), dataIndex: 'operatorId', key: 'operatorId', minWidth: 140, render: (v: string) => <Text code>{v}</Text> },
    { title: t('audit.columns.channel'), dataIndex: 'channel', key: 'channel', minWidth: 70, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('audit.columns.action'), dataIndex: 'action', key: 'action', minWidth: 180, render: (v: string) => <Text code>{v}</Text> },
    { title: t('audit.columns.target'), key: 'target', minWidth: 200, render: (_, record) => (record.targetId ? `${record.targetType}:${record.targetId}` : record.targetType) },
    { title: t('audit.columns.content'), dataIndex: 'content', key: 'content', minWidth: 240, ellipsis: true },
    { title: t('audit.columns.traceId'), dataIndex: 'traceId', key: 'traceId', minWidth: 140, ellipsis: true, render: (v: string) => (v ? <Text code style={{ fontSize: 11 }}>{v.slice(0, 12)}…</Text> : '—') },
  ];

  return (
    <CrudTable<AdminAuditEventView, AuditFilters>
      crud={crud}
      tableKey="audit"
      title={t('audit.title')}
      columns={columns}
      searchable={false}
      reloadAriaLabel={t('audit.reload')}
      totalText={(total) => t('audit.total', { total })}
      filterPanel={
        <Form
          form={form}
          layout="horizontal"
          labelAlign="left"
          colon={false}
          onFinish={(values: AuditFormValues) => {
            // 提交即整组过滤替换并重置到第 1 页（多次 setFilter 在同一事件批内合并为一次拉取）
            crud.setFilter('operatorId', values.operatorId || undefined);
            crud.setFilter('action', values.action || undefined);
            crud.setFilter('traceId', values.traceId || undefined);
            crud.setFilter('from', values.range?.[0]?.toISOString());
            crud.setFilter('to', values.range?.[1]?.toISOString());
          }}
        >
          <FilterPanel
            advancedActive={Boolean(crud.filters.traceId || crud.filters.from || crud.filters.to)}
            advanced={(
              <>
                <Form.Item name="traceId" label={t('audit.filters.traceId')}>
                  <Input placeholder={t('audit.filters.traceId')} allowClear />
                </Form.Item>
                <Form.Item name="range" label={t('common.filters.timeRange')} className="filter-field-wide">
                  <DatePicker.RangePicker showTime />
                </Form.Item>
              </>
            )}
            actions={(
              <Space>
                <Button type="primary" htmlType="submit">{t('audit.filters.query')}</Button>
                <Button onClick={() => { form.resetFields(); crud.resetFilters(); }}>
                  {t('audit.filters.reset')}
                </Button>
              </Space>
            )}
          >
            <Form.Item name="operatorId" label={t('audit.filters.operatorId')}>
              <Input placeholder={t('audit.filters.operatorId')} allowClear />
            </Form.Item>
            <Form.Item name="action" label={t('audit.columns.action')}>
              <Input placeholder={t('audit.filters.action')} allowClear />
            </Form.Item>
          </FilterPanel>
        </Form>
      }
    />
  );
}
