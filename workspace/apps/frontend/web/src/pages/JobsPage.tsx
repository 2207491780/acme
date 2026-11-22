import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, App, Button, Card, DatePicker, Drawer, Form, Input, InputNumber, Popconfirm, Select, Space, Switch, Table, Tag, Tabs, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { ReloadOutlined } from '@ant-design/icons';
import {
  TONE_ANTD,
  Auth,
  formatDate,
  schedulerApi,
  systemErrorMessage,
  useCrud,
  useMeStore,
  type ExecutionState,
  type ExecutionView,
  type ExecutorView,
  type JobAdminView,
  type JobMisfirePolicy,
  type JobSavePayload,
  type JobStatus,
  type JobTriggerType,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';
import { FilterPanel } from '../components/FilterPanel';

const { Text } = Typography;

/** 执行状态 → 语义 Tone（UI-SPEC §2.2 statusTone 先例：SUCCESS=success、FAILED=danger、RUNNING=info、其余 muted）。 */
const statusTone = (status: string | null): keyof typeof TONE_ANTD => {
  const key = (status ?? '').toUpperCase();
  if (key === 'SUCCESS') return 'success';
  if (key === 'FAILED' || key === 'ERROR') return 'danger';
  if (key === 'RUNNING') return 'info';
  return 'muted';
};

/** 执行状态全集（sch_job_execution.state）。 */
const EXECUTION_STATES: ExecutionState[] = ['PENDING', 'DISPATCHED', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED'];
/** 触发类型全集（sch_job_definition.trigger_type）。 */
const TRIGGER_TYPES: JobTriggerType[] = ['CRON', 'FIXED_RATE', 'ONCE'];
/** Misfire 策略全集。 */
const MISFIRE_POLICIES: JobMisfirePolicy[] = ['FIRE_ONCE_NOW', 'IGNORE'];

/** JOB-3 契约⑥：V2 种子 4 行归属应用（启用动作需带接管说明+cron 运维核对提示）。 */
const SEED_APP_NAME = 'platform-infra';

/** 状态 → AntD 预设名（ENABLED=success / DISABLED=default）；单元格文本取状态域字面量。 */
const jobStatusTone = (status: JobStatus): string => (status === 'ENABLED' ? 'success' : 'default');

/** 调度规则渲染：cron 或 fixedRate（契约②「cron 或 fixedRate」合并列）。 */
function renderSchedule(record: JobAdminView): string {
  if (record.cron) return record.cron;
  if (record.fixedRate !== null && record.fixedRate !== undefined) return `${record.fixedRate} ms`;
  return '—';
}

/** 任务定义表单值（cron/fixedRate/onceFireAt 按触发类型条件出现）。 */
interface JobFormValues {
  jobKey: string;
  appName: string;
  triggerType: JobTriggerType;
  cron?: string;
  fixedRate?: number;
  onceFireAt?: Dayjs | null;
  timeoutMs?: number;
  shardTotal?: number;
  misfirePolicy: JobMisfirePolicy;
}

/** 任务定义筛选表单值（查询型提交整组替换）。 */
interface JobFilterFormValues {
  jobKey?: string;
  status?: JobStatus;
}

/** 执行历史筛选表单值（range 提交时序列化 fireTimeFrom/To ISO）。 */
interface ExecutionFilterFormValues {
  jobKey?: string;
  state?: ExecutionState;
  range?: [Dayjs, Dayjs];
}

/** 用 type 而非 interface：类型别名才满足 CrudFilters 的 Record<string, string | undefined> 约束。 */
type JobFilters = { jobKey?: string; status?: string };
type ExecutionFilters = { jobKey?: string; state?: string; fireTimeFrom?: string; fireTimeTo?: string };

/**
 * 任务定义 Tab（REQ-20261122-023213 契约②⑤）：CrudTable 分页（表单型筛选 jobKey/status + 查询/重置，
 * AuditPage/LoginLogsPage 口径）+ 列集按 JobView 精简集 + 行操作（启停 Switch/手动触发/编辑/删除，Auth 75/76 分控）。
 */
function JobDefinitionsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<JobFormValues>();
  const [filterForm] = Form.useForm<JobFilterFormValues>();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<JobAdminView, JobFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      schedulerApi.jobPage(page, size, { jobKey: filters?.jobKey || undefined, status: filters?.status as JobStatus | undefined }, signal),
    filters: { jobKey: undefined, status: undefined },
    permissions,
    errorMessage: t('jobs.errors.loadFailed'),
  });

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<JobAdminView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [triggerType, setTriggerType] = useState<JobTriggerType>('CRON');
  /** 启停乐观态（id→status）：PUT 成功后即时迁移行呈现，列表回读前不闪旧值。 */
  const [statusOverrides, setStatusOverrides] = useState<Record<string, JobStatus>>({});

  /** 行生效状态：乐观态优先（与后端回读一致后自然被覆盖）。 */
  const effectiveStatus = (record: JobAdminView): JobStatus => statusOverrides[record.id] ?? record.status;

  const openCreate = () => {
    setEditing(null);
    setTriggerType('CRON');
    form.resetFields();
    form.setFieldsValue({ triggerType: 'CRON', misfirePolicy: 'FIRE_ONCE_NOW', shardTotal: 1, timeoutMs: 60000 });
    setDrawerOpen(true);
  };

  const openEdit = (record: JobAdminView) => {
    setEditing(record);
    setTriggerType(record.triggerType);
    form.resetFields();
    form.setFieldsValue({
      jobKey: record.jobKey,
      appName: record.appName,
      triggerType: record.triggerType,
      cron: record.cron ?? undefined,
      fixedRate: record.fixedRate ?? undefined,
      onceFireAt: record.onceFireAt ? dayjs(record.onceFireAt) : undefined,
      timeoutMs: record.timeoutMs ?? undefined,
      shardTotal: record.shardTotal,
      // 历史 wire 行可能缺省 misfirePolicy（旧数据/外部桩）：缺省回落配置默认值，保证校验可通过
      misfirePolicy: record.misfirePolicy ?? 'FIRE_ONCE_NOW',
    });
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditing(null);
  };

  const submit = async () => {
    const values = await form.validateFields();
    const payload: JobSavePayload = {
      jobKey: values.jobKey,
      appName: values.appName,
      triggerType: values.triggerType,
      cron: values.triggerType === 'CRON' ? values.cron : null,
      fixedRate: values.triggerType === 'FIXED_RATE' ? values.fixedRate : null,
      onceFireAt: values.triggerType === 'ONCE' && values.onceFireAt ? values.onceFireAt.toISOString() : null,
      shardTotal: values.shardTotal,
      timeoutMs: values.timeoutMs ?? null,
      misfirePolicy: values.misfirePolicy,
    };
    setSubmitting(true);
    try {
      if (editing) {
        await schedulerApi.jobUpdate(editing.id, { ...payload, jobKey: editing.jobKey, version: editing.version });
        message.success(t('jobs.toast.updated'));
      } else {
        await schedulerApi.jobCreate(payload);
        message.success(t('jobs.toast.created'));
      }
      closeDrawer();
      crud.reload();
    } catch (ex: unknown) {
      // 错误码映射展示（契约 AC-5）：KEY_DUPLICATE/KEY_IMMUTABLE 定位到 jobKey，其余统一转译
      const code = (ex as { code?: string })?.code;
      if (code === 'KEY_DUPLICATE') {
        form.setFields([{ name: 'jobKey', errors: [t('jobs.errors.keyDuplicate')] }]);
        message.error(t('jobs.errors.keyDuplicate'));
      } else if (code === 'KEY_IMMUTABLE') {
        message.error(t('jobs.errors.keyImmutable'));
      } else {
        message.error(systemErrorMessage(ex, t('jobs.errors.saveFailed')));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: JobAdminView) => {
    const current = effectiveStatus(record);
    const next: JobStatus = current === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await schedulerApi.jobUpdateStatus(record.id, next);
      // 契约 AC-3「状态即时迁移」：PUT 成功即乐观更新行状态（列表回读后与后端对齐）
      setStatusOverrides((prev) => ({ ...prev, [record.id]: next }));
      message.success(next === 'ENABLED' ? t('jobs.toast.enabled') : t('jobs.toast.disabled'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('jobs.errors.statusFailed')));
    }
  };

  const triggerJob = async (record: JobAdminView) => {
    try {
      const executionId = await schedulerApi.jobTrigger(record.id);
      message.success(t('jobs.toast.triggered', { id: executionId }));
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('jobs.errors.triggerFailed')));
    }
  };

  const deleteJob = async (record: JobAdminView) => {
    try {
      await schedulerApi.jobDelete(record.id);
      message.success(t('jobs.toast.deleted'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('jobs.errors.deleteFailed')));
    }
  };

  const columns: ColumnsType<JobAdminView> = [
    { title: t('jobs.columns.jobKey'), dataIndex: 'jobKey', key: 'jobKey', minWidth: 200, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('jobs.columns.appName'), dataIndex: 'appName', key: 'appName', minWidth: 140 },
    { title: t('jobs.columns.triggerType'), dataIndex: 'triggerType', key: 'triggerType', minWidth: 110, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('jobs.columns.schedule'), key: 'schedule', minWidth: 150, render: (_, record) => <Text code style={{ fontSize: 11 }}>{renderSchedule(record)}</Text> },
    { title: t('jobs.columns.nextFireTime'), dataIndex: 'nextFireTime', key: 'nextFireTime', minWidth: 170, render: (v: string | null) => (v ? formatDate(v) : '—') },
    {
      title: t('jobs.columns.status'), dataIndex: 'status', key: 'status', minWidth: 100,
      render: (_, record) => {
        const status = effectiveStatus(record);
        return <Tag color={jobStatusTone(status)}>{status}</Tag>;
      },
    },
    { title: t('jobs.columns.timeoutMs'), dataIndex: 'timeoutMs', key: 'timeoutMs', minWidth: 100, render: (v: number | null) => (v === null || v === undefined ? '—' : v) },
    { title: t('jobs.columns.shardTotal'), dataIndex: 'shardTotal', key: 'shardTotal', minWidth: 90 },
    {
      title: t('jobs.columns.actions'), key: 'actions', minWidth: 260, fixed: 'right',
      render: (_, record) => {
        // 契约⑥：appName=platform-infra 且 DISABLED 的种子行启用确认需带接管说明+cron 运维核对提示
        const status = effectiveStatus(record);
        const isSeedDisabled = record.appName === SEED_APP_NAME && status === 'DISABLED';
        const confirmTitle = isSeedDisabled
          ? t('jobs.status.takeoverTitle')
          : status === 'ENABLED'
            ? t('jobs.status.disableConfirm', { jobKey: record.jobKey })
            : t('jobs.status.enableConfirm', { jobKey: record.jobKey });
        return (
          <Space size={8}>
            <Auth code="scheduler:job:update">
              <Popconfirm
                title={confirmTitle}
                description={isSeedDisabled ? t('jobs.status.takeoverHint', { cron: record.cron ?? '—' }) : undefined}
                onConfirm={() => void toggleStatus(record)}
              >
                <Switch size="small" checked={status === 'ENABLED'} aria-label={t('jobs.columns.status')} />
              </Popconfirm>
            </Auth>
            <Auth code="scheduler:job:update">
              <Popconfirm title={t('jobs.actions.triggerConfirm')} onConfirm={() => void triggerJob(record)}>
                <Button size="small">{t('jobs.actions.trigger')}</Button>
              </Popconfirm>
            </Auth>
            <Auth code="scheduler:job:update">
              <Button size="small" onClick={() => openEdit(record)}>{t('jobs.actions.edit')}</Button>
            </Auth>
            <Auth code="scheduler:job:delete">
              <Popconfirm title={t('jobs.actions.deleteConfirm', { jobKey: record.jobKey })} onConfirm={() => void deleteJob(record)}>
                <Button size="small" danger>{t('common.actions.delete')}</Button>
              </Popconfirm>
            </Auth>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <CrudTable<JobAdminView, JobFilters>
        crud={crud}
        tableKey="scheduler-jobs"
        title={t('jobs.tabs.definitions')}
        columns={columns}
        reloadAriaLabel={t('jobs.reload')}
        totalText={(total) => t('jobs.total', { total })}
        createPermission="scheduler:job:update"
        createLabel={t('jobs.actions.create')}
        onCreate={openCreate}
        filterPanel={
          <Form
            form={filterForm}
            layout="horizontal"
            labelAlign="left"
            colon={false}
            onFinish={(values: JobFilterFormValues) => {
              // 提交即整组过滤替换并重置到第 1 页（多次 setFilter 在同一事件批内合并为一次拉取）
              crud.setFilter('jobKey', values.jobKey || undefined);
              crud.setFilter('status', values.status || undefined);
            }}
          >
            <FilterPanel
              advancedActive={Boolean(crud.filters.jobKey || crud.filters.status)}
              actions={(
                <Space>
                  <Button type="primary" htmlType="submit">{t('common.actions.query')}</Button>
                  <Button onClick={() => { filterForm.resetFields(); crud.resetFilters(); }}>
                    {t('common.actions.reset')}
                  </Button>
                </Space>
              )}
            >
              <Form.Item name="jobKey" label={t('jobs.filters.jobKey')}>
                <Input placeholder={t('jobs.filters.jobKey')} allowClear autoComplete="off" />
              </Form.Item>
              <Form.Item name="status" label={t('jobs.filters.status')}>
                {/* 选项取状态域字面量（与列表单元格一致）；virtual=false 免隐藏 a11y listbox 重复文本歧义 */}
                <Select
                  virtual={false}
                  allowClear
                  placeholder={t('common.validation.optional')}
                  options={[{ value: 'ENABLED', label: 'ENABLED' }, { value: 'DISABLED', label: 'DISABLED' }]}
                />
              </Form.Item>
            </FilterPanel>
          </Form>
        }
      />

      <Drawer
        open={drawerOpen}
        onClose={closeDrawer}
        title={editing ? t('jobs.form.editTitle', { jobKey: editing.jobKey }) : t('jobs.form.createTitle')}
        width={520}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={() => void submit()}>
          <Form.Item
            name="jobKey"
            label={t('jobs.form.jobKey')}
            rules={[{ required: true, message: t('common.validation.optional') }]}
            extra={editing ? t('jobs.form.jobKeyImmutable') : undefined}
          >
            {/* 契约⑤：job_key 编辑态只读（KEY_IMMUTABLE 由后端兜底，前端编辑态直接锁定） */}
            <Input placeholder={t('jobs.form.jobKeyPlaceholder')} disabled={editing !== null} autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="appName"
            label={t('jobs.form.appName')}
            rules={[{ required: true, message: t('common.validation.optional') }]}
          >
            <Input placeholder={t('jobs.form.appNamePlaceholder')} autoComplete="off" />
          </Form.Item>
          <Form.Item name="triggerType" label={t('jobs.form.triggerType')} rules={[{ required: true }]}>
            <Select
              options={TRIGGER_TYPES.map((value) => ({ value, label: value }))}
              onChange={(value: JobTriggerType) => setTriggerType(value)}
            />
          </Form.Item>
          {triggerType === 'CRON' ? (
            <Form.Item name="cron" label={t('jobs.form.cron')} rules={[{ required: true, message: t('common.validation.optional') }]}>
              <Input placeholder={t('jobs.form.cronPlaceholder')} autoComplete="off" />
            </Form.Item>
          ) : null}
          {triggerType === 'FIXED_RATE' ? (
            <Form.Item name="fixedRate" label={t('jobs.form.fixedRate')} rules={[{ required: true, message: t('common.validation.optional') }]}>
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          ) : null}
          {triggerType === 'ONCE' ? (
            <Form.Item name="onceFireAt" label={t('jobs.form.onceFireAt')} rules={[{ required: true, message: t('common.validation.optional') }]}>
              <DatePicker showTime style={{ width: '100%' }} />
            </Form.Item>
          ) : null}
          <Form.Item name="timeoutMs" label={t('jobs.form.timeoutMs')}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="shardTotal" label={t('jobs.form.shardTotal')} rules={[{ required: true, message: t('common.validation.optional') }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="misfirePolicy" label={t('jobs.form.misfirePolicy')} rules={[{ required: true }]}>
            <Select options={MISFIRE_POLICIES.map((value) => ({ value, label: value }))} />
          </Form.Item>
          {editing ? (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('common.validation.optimisticLock', { version: editing.version })}{t('common.validation.optimisticLockConflict')}
            </Text>
          ) : null}
          <Space style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
            <Button onClick={closeDrawer}>{t('common.actions.cancel')}</Button>
            <Button type="primary" loading={submitting} onClick={() => void submit()}>{t('common.actions.confirm')}</Button>
          </Space>
        </Form>
      </Drawer>
    </>
  );
}

/**
 * 执行历史 Tab（契约②）：useCrud 分页 + 表单型筛选 jobKey/state/fireTime 窗口（查询/重置）
 * （读 sch_job_execution 面 GET /admin/v1/scheduler/executions，fire_time 倒序在后端）。
 */
function JobExecutionsTab() {
  const { t } = useTranslation();
  const [filterForm] = Form.useForm<ExecutionFilterFormValues>();
  const filterWrapRef = useRef<HTMLDivElement>(null);

  const crud = useCrud<ExecutionView, ExecutionFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      schedulerApi.executionPage(
        page,
        size,
        {
          jobKey: filters?.jobKey || undefined,
          state: filters?.state as ExecutionState | undefined,
          fireTimeFrom: filters?.fireTimeFrom,
          fireTimeTo: filters?.fireTimeTo,
        },
        signal,
      ),
    filters: { jobKey: undefined, state: undefined, fireTimeFrom: undefined, fireTimeTo: undefined },
    errorMessage: t('jobs.errors.executionsLoadFailed'),
  });

  const columns: ColumnsType<ExecutionView> = [
    { title: t('jobs.executions.columns.jobKey'), dataIndex: 'jobKey', key: 'jobKey', minWidth: 200, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('jobs.executions.columns.triggerType'), dataIndex: 'triggerType', key: 'triggerType', minWidth: 100, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('jobs.executions.columns.fireTime'), dataIndex: 'fireTime', key: 'fireTime', minWidth: 170, render: (v: string) => formatDate(v) },
    { title: t('jobs.executions.columns.state'), dataIndex: 'state', key: 'state', minWidth: 110, render: (v: string) => <Tag color={TONE_ANTD[statusTone(v)]}>{v}</Tag> },
    { title: t('jobs.executions.columns.attempt'), dataIndex: 'attempt', key: 'attempt', minWidth: 90 },
    { title: t('jobs.executions.columns.shardIndex'), dataIndex: 'shardIndex', key: 'shardIndex', minWidth: 90, render: (v: number | null) => (v === null || v === undefined ? '—' : v) },
    { title: t('jobs.executions.columns.costMs'), dataIndex: 'costMs', key: 'costMs', minWidth: 100, render: (v: number | null) => (v === null || v === undefined ? '—' : `${v} ms`) },
    { title: t('jobs.executions.columns.error'), dataIndex: 'error', key: 'error', minWidth: 220, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('jobs.executions.columns.traceId'), dataIndex: 'traceId', key: 'traceId', minWidth: 160, ellipsis: true, render: (v: string | null) => v ?? '—' },
  ];

  return (
    <CrudTable<ExecutionView, ExecutionFilters>
      crud={crud}
      tableKey="scheduler-executions"
      title={t('jobs.tabs.executions')}
      columns={columns}
      reloadAriaLabel={t('jobs.executions.reload')}
      totalText={(total) => t('common.table.total', { total })}
      filterPanel={
        <div ref={filterWrapRef}>
        <Form
          form={filterForm}
          layout="horizontal"
          labelAlign="left"
          colon={false}
          onFinish={(values: ExecutionFilterFormValues) => {
            // 提交即整组过滤替换并重置到第 1 页（多次 setFilter 在同一事件批内合并为一次拉取）
            let from = values.range?.[0]?.toISOString();
            let to = values.range?.[1]?.toISOString();
            // RangePicker 键入未提交兜底（commit-on-submit）：部分宿主对 picker 输入的 change
            // 不触发面板提交，此时若表单 range 为空则解析键入文本（合法才采纳）
            if (!from || !to) {
              const inputs = filterWrapRef.current?.querySelectorAll<HTMLInputElement>('.ant-picker input');
              const parseText = (text: string | undefined | null): string | undefined => {
                if (!text) return undefined;
                const day = dayjs(text);
                return day.isValid() ? day.toISOString() : undefined;
              };
              if (!from) from = parseText(inputs?.[0]?.value);
              if (!to) to = parseText(inputs?.[1]?.value);
            }
            crud.setFilter('jobKey', values.jobKey || undefined);
            crud.setFilter('state', values.state || undefined);
            crud.setFilter('fireTimeFrom', from);
            crud.setFilter('fireTimeTo', to);
          }}
        >
          <FilterPanel
            advancedActive={Boolean(crud.filters.jobKey || crud.filters.state || crud.filters.fireTimeFrom || crud.filters.fireTimeTo)}
            actions={(
              <Space>
                <Button type="primary" htmlType="submit">{t('common.actions.query')}</Button>
                <Button onClick={() => { filterForm.resetFields(); crud.resetFilters(); }}>
                  {t('common.actions.reset')}
                </Button>
              </Space>
            )}
          >
            <Form.Item name="jobKey" label={t('jobs.filters.jobKey')}>
              <Input placeholder={t('jobs.filters.jobKey')} allowClear autoComplete="off" />
            </Form.Item>
            <Form.Item name="state" label={t('jobs.filters.state')}>
              <Select
                virtual={false}
                allowClear
                placeholder={t('common.validation.optional')}
                options={EXECUTION_STATES.map((value) => ({ value, label: value }))}
              />
            </Form.Item>
            <Form.Item name="range" label={t('jobs.filters.fireTimeRange')} className="filter-field-wide">
              <DatePicker.RangePicker showTime />
            </Form.Item>
          </FilterPanel>
        </Form>
        </div>
      }
    />
  );
}

/** 执行器在线状态读取（wire 字段名容错：status 为主口径，state 兼容回退）。 */
const executorStatusOf = (record: ExecutorView): 'ONLINE' | 'OFFLINE' | null =>
  record.status ?? (record as { state?: 'ONLINE' | 'OFFLINE' }).state ?? null;

/**
 * 执行器监控 Tab（契约②⑤，纳入一期）：executors 列表 + 在线状态徽标
 * （ONLINE=success / OFFLINE=default，TONE_ANTD 语义映射；单元格文本取在线状态域字面量）。
 */
function JobExecutorsTab() {
  const { t } = useTranslation();
  const [rows, setRows] = useState<ExecutorView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    schedulerApi.executorList()
      .then((list) => setRows(list))
      .catch(() => setError(t('jobs.errors.executorsLoadFailed')))
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  const columns: ColumnsType<ExecutorView> = [
    { title: t('jobs.executors.columns.appName'), dataIndex: 'appName', key: 'appName', minWidth: 160, render: (v: string) => <Text code>{v}</Text> },
    { title: t('jobs.executors.columns.instanceId'), dataIndex: 'instanceId', key: 'instanceId', minWidth: 240, ellipsis: true, render: (v: string | undefined) => v ?? '—' },
    { title: t('jobs.executors.columns.host'), dataIndex: 'host', key: 'host', minWidth: 140, render: (v: string | undefined) => v ?? '—' },
    { title: t('jobs.executors.columns.port'), dataIndex: 'port', key: 'port', minWidth: 80, render: (v: number | undefined) => (v === null || v === undefined ? '—' : v) },
    { title: t('jobs.executors.columns.handlers'), dataIndex: 'handlers', key: 'handlers', minWidth: 260, ellipsis: true, render: (v: string[] | undefined) => (Array.isArray(v) && v.length ? v.join(', ') : '—') },
    { title: t('jobs.executors.columns.heartbeatAt'), dataIndex: 'heartbeatAt', key: 'heartbeatAt', minWidth: 170, render: (v: string | null | undefined) => (v ? formatDate(v) : '—') },
    {
      title: t('jobs.executors.columns.status'), dataIndex: 'status', key: 'status', minWidth: 100,
      render: (_, record) => {
        const status = executorStatusOf(record);
        return <Tag color={status === 'ONLINE' ? 'success' : 'default'}>{status ?? '—'}</Tag>;
      },
    },
  ];

  return (
    <Card
      title={t('jobs.tabs.executors')}
      extra={
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading} aria-label={t('jobs.executors.reload')} />
      }
    >
      {error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={error}
          action={<Button size="small" onClick={load}>{t('common.actions.retry')}</Button>}
        />
      ) : null}
      <Table<ExecutorView>
        rowKey={(record) => `${record.appName}:${record.instanceId}`}
        columns={columns}
        dataSource={rows}
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
        pagination={false}
        footer={() => t('jobs.executors.total', { total: rows.length })}
      />
    </Card>
  );
}

/**
 * 调度中心（REQ-20261122-023213 JOB-3）：/jobs 单页三 Tab（任务定义/执行历史/执行器监控），
 * 全量切读 /admin/v1/scheduler/**（契约②③：旧 infra 面保留但页面不再消费）。
 * 权限分控：列表 73（路由 PermissionGate）+ status/trigger/新建/编辑 75 + 删除 76（契约⑤）。
 */
export function JobsPage() {
  const { t } = useTranslation();
  const tabItems = useMemo(
    () => [
      { key: 'definitions', label: t('jobs.tabs.definitions'), children: <JobDefinitionsTab /> },
      { key: 'executions', label: t('jobs.tabs.executions'), children: <JobExecutionsTab /> },
      { key: 'executors', label: t('jobs.tabs.executors'), children: <JobExecutorsTab /> },
    ],
    [t],
  );

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>{t('jobs.title')}</Typography.Title>
      {/* destroyOnHidden：切 Tab 即卸载非活动 pane（三 Tab 内容域独立，避免跨 Tab 文本歧义） */}
      <Tabs defaultActiveKey="definitions" items={tabItems} destroyOnHidden />
    </div>
  );
}
