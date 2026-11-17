import { App, Button, DatePicker, Form, Input, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import {
  auditApi,
  formatDate,
  useCrud,
  useExcelExport,
  useMeStore,
  type AdminAuditEventView,
} from '@acme/core';
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
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<AuditFormValues>();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

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
    permissions,
    errorMessage: t('audit.errors.loadFailed'),
  });

  const { exporting, exportFile } = useExcelExport();

  const doExport = async () => {
    // 导出透传当前已提交筛选（crud.filters），与列表口径一致（权限复用 infra:audit:list）
    const ok = await exportFile(auditApi.eventsExport(crud.filters), 'audit-events.xlsx');
    if (ok) {
      message.success(t('audit.toast.exported'));
    } else {
      message.error(t('audit.errors.exportFailed'));
    }
  };

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
      title={t('audit.title')}
      columns={columns}
      searchable={false}
      reloadAriaLabel={t('audit.reload')}
      totalText={(total) => t('audit.total', { total })}
      actions={crud.can('infra:audit:list') ? (
        <Button loading={exporting} onClick={() => void doExport()}>{t('audit.actions.export')}</Button>
      ) : null}
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
            <Input placeholder={t('audit.filters.operatorId')} allowClear style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="action">
            <Input placeholder={t('audit.filters.action')} allowClear style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="traceId">
            <Input placeholder={t('audit.filters.traceId')} allowClear style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="range">
            <DatePicker.RangePicker showTime style={{ width: 340 }} />
          </Form.Item>
          <Space size={4}>
            <Button type="primary" htmlType="submit">{t('audit.filters.query')}</Button>
            <Button
              onClick={() => {
                form.resetFields();
                crud.resetFilters();
              }}
            >
              {t('audit.filters.reset')}
            </Button>
          </Space>
        </Form>
      }
    />
  );
}
