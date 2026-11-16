import { Button, DatePicker, Form, Input, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { authApi, formatDate, useCrud, type AdminLoginLogView } from '@acme/core';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

/** 已提交查询过滤（range 在提交时序列化为 from/to ISO 串，适配 useCrud 字符串筛选形状）。
 *  用 type 而非 interface：类型别名才满足 CrudFilters 的 Record<string, string | undefined> 约束。 */
type LoginLogFilters = {
  username?: string;
  userType?: string;
  result?: string;
  from?: string;
  to?: string;
};

interface LoginFormValues {
  username?: string;
  userType?: string;
  result?: string;
  range?: [Dayjs, Dayjs];
}

/** 登录日志查询（REQ-20261114-120105，权限点 auth:loginlog:list）：
 *  分页 + 筛选（username/userType/result/时间区间），迁移 CRUD 范本（AuditPage 口径）：
 *  useCrud（表单筛选走 filters，提交重置第 1 页）+ CrudTable（查询型页面无新建/删除）。 */
export function LoginLogsPage() {
  const { t } = useTranslation();
  const [form] = Form.useForm<LoginFormValues>();

  const crud = useCrud<AdminLoginLogView, LoginLogFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      authApi.loginLogs(page, size, {
        username: filters?.username,
        userType: filters?.userType,
        result: filters?.result,
        from: filters?.from,
        to: filters?.to,
      }, signal),
    filters: { username: undefined, userType: undefined, result: undefined, from: undefined, to: undefined },
    pageSize: 20,
    errorMessage: t('loginLogs.errors.loadFailed'),
  });

  const columns: ColumnsType<AdminLoginLogView> = [
    { title: t('loginLogs.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 170, fixed: 'left', render: (v: string) => formatDate(v) },
    { title: t('loginLogs.columns.username'), dataIndex: 'username', key: 'username', minWidth: 140, render: (v: string) => <Text code>{v}</Text> },
    { title: t('loginLogs.columns.userType'), dataIndex: 'userType', key: 'userType', minWidth: 80, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('loginLogs.columns.ip'), dataIndex: 'ip', key: 'ip', minWidth: 130 },
    { title: t('loginLogs.columns.userAgent'), dataIndex: 'userAgent', key: 'userAgent', minWidth: 240, ellipsis: true },
    {
      title: t('loginLogs.columns.result'), dataIndex: 'result', key: 'result', minWidth: 100,
      render: (v: string) => <Tag color={v === 'SUCCESS' ? 'success' : 'error'}>{v}</Tag>,
    },
    { title: t('loginLogs.columns.failReason'), dataIndex: 'failReason', key: 'failReason', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('loginLogs.columns.traceId'), dataIndex: 'traceId', key: 'traceId', minWidth: 140, ellipsis: true, render: (v: string | null) => (v ? <Text code style={{ fontSize: 11 }}>{v.slice(0, 12)}…</Text> : '—') },
  ];

  return (
    <CrudTable<AdminLoginLogView, LoginLogFilters>
      crud={crud}
      title={t('loginLogs.title')}
      columns={columns}
      searchable={false}
      reloadAriaLabel={t('loginLogs.reload')}
      totalText={(total) => t('loginLogs.total', { total })}
      filterPanel={
        <Form
          form={form}
          layout="inline"
          style={{ marginBottom: 16, flexWrap: 'wrap', rowGap: 8 }}
          onFinish={(values: LoginFormValues) => {
            // 提交即整组过滤替换并重置到第 1 页（多次 setFilter 在同一事件批内合并为一次拉取）
            crud.setFilter('username', values.username || undefined);
            crud.setFilter('userType', values.userType || undefined);
            crud.setFilter('result', values.result || undefined);
            crud.setFilter('from', values.range?.[0]?.toISOString());
            crud.setFilter('to', values.range?.[1]?.toISOString());
          }}
        >
          <Form.Item name="username">
            <Input placeholder={t('loginLogs.filters.username')} allowClear style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="userType">
            <Input placeholder={t('loginLogs.filters.userType')} allowClear style={{ width: 140 }} />
          </Form.Item>
          <Form.Item name="result">
            <Input placeholder={t('loginLogs.filters.result')} allowClear style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="range">
            <DatePicker.RangePicker showTime style={{ width: 340 }} />
          </Form.Item>
          <Space size={4}>
            <Button type="primary" htmlType="submit">{t('loginLogs.filters.query')}</Button>
            <Button
              onClick={() => {
                form.resetFields();
                crud.resetFilters();
              }}
            >
              {t('loginLogs.filters.reset')}
            </Button>
          </Space>
        </Form>
      }
    />
  );
}
