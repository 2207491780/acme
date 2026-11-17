import { useState } from 'react';
import { App, Button, DatePicker, Descriptions, Drawer, Form, Input, Space, Spin, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import {
  TONE_ANTD,
  formatDate,
  infraApi,
  systemErrorMessage,
  useCrud,
  useExcelExport,
  useMeStore,
  type AdminOpExceptionView,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

/** 已提交查询过滤（range 在提交时序列化为 from/to ISO 串，适配 useCrud 字符串筛选形状）。 */
type ExceptionFilters = {
  service?: string;
  traceId?: string;
  exceptionType?: string;
  from?: string;
  to?: string;
};

interface ExceptionFormValues {
  service?: string;
  traceId?: string;
  exceptionType?: string;
  range?: [Dayjs, Dayjs];
}

/** 状态码 → 语义 Tone（UI-SPEC §2.2：5xx=danger 红、4xx=warning、其余=success；经 TONE_ANTD 预设名渲染）。 */
const statusCodeTone = (code: number) => (code >= 500 ? 'danger' : code >= 400 ? 'warning' : 'success') as keyof typeof TONE_ANTD;

/**
 * 运行异常日志（REQ-20261117-061017，权限点 infra:exception:list）：
 * 分页 + 筛选（service/traceId/exceptionType/时间区间）+ 详情抽屉（stackSummary 全文，pre 滚动）
 * + 导出（xlsx 附件，同筛选参数透传，useExcelExport → downloadFile）。
 * CRUD 范本对齐 AuditPage（查询型页面：无新建/删除；表单筛选走 filters，提交重置第 1 页）。
 */
export function ExceptionsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<ExceptionFormValues>();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminOpExceptionView, ExceptionFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      infraApi.opExceptionList(page, size, {
        service: filters?.service,
        traceId: filters?.traceId,
        exceptionType: filters?.exceptionType,
        from: filters?.from,
        to: filters?.to,
      }, signal),
    filters: { service: undefined, traceId: undefined, exceptionType: undefined, from: undefined, to: undefined },
    pageSize: 20,
    permissions,
    errorMessage: t('opExceptions.errors.loadFailed'),
  });

  const { exporting, exportFile } = useExcelExport();

  // 详情抽屉：列表行 stackSummary 可能为截断摘要 → 打开即拉详情端点取全文（失败降级展示行内摘要）
  const [detailRow, setDetailRow] = useState<AdminOpExceptionView | null>(null);
  const [detailFull, setDetailFull] = useState<AdminOpExceptionView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const openDetail = (record: AdminOpExceptionView) => {
    setDetailRow(record);
    setDetailFull(null);
    setDetailError(null);
    setDetailLoading(true);
    infraApi.opExceptionGet(record.id)
      .then((view) => setDetailFull(view))
      .catch((ex: unknown) => setDetailError(systemErrorMessage(ex, t('opExceptions.detail.loadFailed'))))
      .finally(() => setDetailLoading(false));
  };

  const closeDetail = () => {
    setDetailRow(null);
    setDetailFull(null);
    setDetailError(null);
  };

  const doExport = async () => {
    // 导出透传当前已提交筛选（crud.filters），与列表口径一致
    const ok = await exportFile(infraApi.opExceptionExport(crud.filters), 'op-exceptions.xlsx');
    if (ok) {
      message.success(t('opExceptions.toast.exported'));
    } else {
      message.error(t('opExceptions.errors.exportFailed'));
    }
  };

  const columns: ColumnsType<AdminOpExceptionView> = [
    { title: t('opExceptions.columns.occurredAt'), dataIndex: 'occurredAt', key: 'occurredAt', minWidth: 170, fixed: 'left', render: (v: string) => formatDate(v) },
    { title: t('opExceptions.columns.service'), dataIndex: 'service', key: 'service', minWidth: 140, render: (v: string) => <Text code>{v}</Text> },
    { title: t('opExceptions.columns.method'), dataIndex: 'method', key: 'method', minWidth: 90, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('opExceptions.columns.path'), dataIndex: 'path', key: 'path', minWidth: 220, ellipsis: true, render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
    { title: t('opExceptions.columns.statusCode'), dataIndex: 'statusCode', key: 'statusCode', minWidth: 90, render: (v: number) => <Tag color={TONE_ANTD[statusCodeTone(Number(v))]}>{v}</Tag> },
    { title: t('opExceptions.columns.exceptionType'), dataIndex: 'exceptionType', key: 'exceptionType', minWidth: 220, ellipsis: true, render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
    { title: t('opExceptions.columns.message'), dataIndex: 'message', key: 'message', minWidth: 240, ellipsis: true },
    { title: t('opExceptions.columns.traceId'), dataIndex: 'traceId', key: 'traceId', minWidth: 140, ellipsis: true, render: (v: string) => (v ? <Text code style={{ fontSize: 11 }}>{v.slice(0, 12)}…</Text> : '—') },
    {
      title: t('opExceptions.columns.actions'), key: 'actions', minWidth: 90, fixed: 'right',
      render: (_, record) => (
        <Button size="small" onClick={(e) => { e.stopPropagation(); openDetail(record); }}>
          {t('opExceptions.actions.detail')}
        </Button>
      ),
    },
  ];

  const detail = detailFull ?? detailRow;

  return (
    <>
      <CrudTable<AdminOpExceptionView, ExceptionFilters>
        crud={crud}
        title={t('opExceptions.title')}
        columns={columns}
        searchable={false}
        reloadAriaLabel={t('opExceptions.reload')}
        totalText={(total) => t('opExceptions.total', { total })}
        onRow={(record) => ({ onClick: () => openDetail(record) })}
        actions={crud.can('infra:exception:list') ? (
          <Button loading={exporting} onClick={() => void doExport()}>{t('opExceptions.actions.export')}</Button>
        ) : null}
        filterPanel={
          <Form
            form={form}
            layout="inline"
            style={{ marginBottom: 16, flexWrap: 'wrap', rowGap: 8 }}
            onFinish={(values: ExceptionFormValues) => {
              // 提交即整组过滤替换并重置到第 1 页（多次 setFilter 在同一事件批内合并为一次拉取）
              crud.setFilter('service', values.service || undefined);
              crud.setFilter('traceId', values.traceId || undefined);
              crud.setFilter('exceptionType', values.exceptionType || undefined);
              crud.setFilter('from', values.range?.[0]?.toISOString());
              crud.setFilter('to', values.range?.[1]?.toISOString());
            }}
          >
            <Form.Item name="service">
              <Input placeholder={t('opExceptions.filters.service')} allowClear style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="traceId">
              <Input placeholder={t('opExceptions.filters.traceId')} allowClear style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="exceptionType">
              <Input placeholder={t('opExceptions.filters.exceptionType')} allowClear style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="range">
              <DatePicker.RangePicker showTime style={{ width: 340 }} />
            </Form.Item>
            <Space size={4}>
              <Button type="primary" htmlType="submit">{t('opExceptions.filters.query')}</Button>
              <Button
                onClick={() => {
                  form.resetFields();
                  crud.resetFilters();
                }}
              >
                {t('opExceptions.filters.reset')}
              </Button>
            </Space>
          </Form>
        }
      />
      <Drawer
        open={detailRow !== null}
        onClose={closeDetail}
        width={640}
        title={t('opExceptions.detail.title')}
        destroyOnHidden
      >
        {detail ? (
          <>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label={t('opExceptions.detail.service')}>{detail.service}</Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.statusCode')}>
                <Tag color={TONE_ANTD[statusCodeTone(Number(detail.statusCode))]}>{detail.statusCode}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.methodPath')} span={2}>
                <Text code style={{ fontSize: 12 }}>{`${detail.method} ${detail.path}`}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.exceptionType')} span={2}>
                <Text code style={{ fontSize: 12 }}>{detail.exceptionType}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.message')} span={2}>{detail.message}</Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.traceId')}>
                <Text code style={{ fontSize: 11 }}>{detail.traceId || '—'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.spanId')}>
                <Text code style={{ fontSize: 11 }}>{detail.spanId || '—'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.tenantId')}>{detail.tenantId ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.operatorId')}>{detail.operatorId ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('opExceptions.detail.occurredAt')} span={2}>{formatDate(detail.occurredAt)}</Descriptions.Item>
            </Descriptions>
            {detailLoading ? (
              <div style={{ textAlign: 'center', padding: 24 }}><Spin tip={t('opExceptions.detail.loading')} /></div>
            ) : detailError ? (
              <Text type="danger" style={{ display: 'block', marginTop: 16 }}>{detailError}</Text>
            ) : (
              <>
                <Text type="secondary" strong style={{ display: 'block', marginTop: 16, marginBottom: 8 }}>
                  {t('opExceptions.detail.stackSummary')}
                </Text>
                <pre
                  style={{
                    margin: 0,
                    maxHeight: 320,
                    overflow: 'auto',
                    padding: 12,
                    borderRadius: 8,
                    border: '1px solid var(--border)',
                    background: 'var(--bg-1)',
                    fontSize: 12,
                    lineHeight: 1.5,
                    fontFamily: 'var(--mono)',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                  }}
                >
                  {detail.stackSummary || t('opExceptions.detail.noStack')}
                </pre>
              </>
            )}
          </>
        ) : null}
      </Drawer>
    </>
  );
}
