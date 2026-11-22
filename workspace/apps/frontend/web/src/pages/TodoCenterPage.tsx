import { useCallback, useState, type ReactElement } from 'react';
import { Button, Form, Input, Segmented, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import type { FormInstance } from 'antd';
import {
  Auth, formatDate, instanceApi, taskApi, TONE_ANTD, useCrud,
  type CrudController, type ProcessInstanceStatus, type ProcessInstanceView,
  type TaskView, type WorkflowTaskStatus,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';
import { FilterPanel } from '../components/FilterPanel';
import { ApprovalDetailDrawer } from './workflow/ApprovalDetailDrawer';
import { InstanceDetailDrawer } from './workflow/InstanceMonitorTab';

const { Text } = Typography;

/**
 * 待办中心页（REQ-20261122-150750 契约②重构，/workflow/todos 独立路由）：
 * Segmented 三子区段（我的待办/已办/我发起）+ 列表精简单入口化——
 * 待办行「办理」单按钮（打开审批详情 Drawer，操作收编 Drawer 操作区按 status 与 82 分控）、
 * done 行「查看」只读、我发起行「查看」（复用实例详情 Drawer readonly 三件套，AC-6）。
 */

const TASK_OPERATE_PERM = 'workflow:task:operate';

/** 任务状态 → 语义 Tone（CREATED=muted/CLAIMED=info/COMPLETED=success/REJECTED=danger/CANCELLED=muted）。 */
function taskStatusTone(status: WorkflowTaskStatus): keyof typeof TONE_ANTD {
  switch (status) {
    case 'CLAIMED': return 'info';
    case 'COMPLETED': return 'success';
    case 'REJECTED': return 'danger';
    default: return 'muted';
  }
}

const TASK_STATUSES: WorkflowTaskStatus[] = ['CREATED', 'CLAIMED', 'COMPLETED', 'REJECTED', 'CANCELLED'];

/** 任务状态 → AntD 预设名（单元格文本取状态域字面量）。 */
const statusBadge = (status: WorkflowTaskStatus): string => TONE_ANTD[taskStatusTone(status)];

/** 任务筛选表单值。 */
interface TaskFilterFormValues {
  definitionKey?: string;
  status?: WorkflowTaskStatus;
}

type TaskFilters = { definitionKey?: string; status?: string };

/** 任务域筛选面板（todo/done 同过滤口径，表单型显式提交 JobsPage 口径）。 */
function TaskFilterPanel({ crud, filterForm }: {
  crud: CrudController<TaskView, TaskFilters>;
  filterForm: FormInstance<TaskFilterFormValues>;
}): ReactElement {
  const { t } = useTranslation();
  return (
    <Form
      form={filterForm}
      layout="horizontal"
      labelAlign="left"
      colon={false}
      onFinish={(values: TaskFilterFormValues) => {
        crud.setFilter('definitionKey', values.definitionKey || undefined);
        crud.setFilter('status', values.status || undefined);
      }}
    >
      <FilterPanel
        advancedActive={Boolean(crud.filters.definitionKey || crud.filters.status)}
        actions={(
          <Space>
            <Button type="primary" htmlType="submit">{t('common.actions.query')}</Button>
            <Button onClick={() => { filterForm.resetFields(); crud.resetFilters(); }}>
              {t('common.actions.reset')}
            </Button>
          </Space>
        )}
      >
        <Form.Item name="definitionKey" label={t('workflow.todo.filters.definitionKey')}>
          <Input allowClear autoComplete="off" />
        </Form.Item>
        <Form.Item name="status" label={t('workflow.todo.filters.status')}>
          <Select
            virtual={false}
            allowClear
            placeholder={t('common.validation.optional')}
            options={TASK_STATUSES.map((value) => ({ value, label: t(`workflow.todo.status.${value}`) }))}
          />
        </Form.Item>
      </FilterPanel>
    </Form>
  );
}

/** 我的待办子区段（列表精简：行内仅「办理」单入口，claim/unclaim/complete/reject/transfer/delegate 收编 Drawer——契约②）。 */
function MyTodosSection(): ReactElement {
  const { t } = useTranslation();
  const [filterForm] = Form.useForm<TaskFilterFormValues>();
  const [detailTask, setDetailTask] = useState<TaskView | null>(null);

  const crud = useCrud<TaskView, TaskFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      taskApi.todo(page, size, {
        definitionKey: filters?.definitionKey || undefined,
        status: (filters?.status as WorkflowTaskStatus | undefined) ?? undefined,
      }, signal),
    filters: { definitionKey: undefined, status: undefined },
    errorMessage: t('workflow.todo.errors.loadFailed'),
  });

  const closeDetail = useCallback(() => setDetailTask(null), []);

  const columns: ColumnsType<TaskView> = [
    { title: t('workflow.todo.columns.nodeName'), dataIndex: 'nodeName', key: 'nodeName', minWidth: 150, fixed: 'left', render: (v: string | null, record) => (v || record.nodeId) },
    { title: t('workflow.todo.columns.definitionName'), dataIndex: 'definitionName', key: 'definitionName', minWidth: 150, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('workflow.todo.columns.businessKey'), dataIndex: 'businessKey', key: 'businessKey', minWidth: 170, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('workflow.todo.columns.initiator'), dataIndex: 'initiator', key: 'initiator', minWidth: 110, render: (v: string | null) => v ?? '—' },
    { title: t('workflow.todo.columns.assignee'), dataIndex: 'assignee', key: 'assignee', minWidth: 110, render: (v: string | null) => v ?? '—' },
    {
      title: t('workflow.todo.columns.status'), dataIndex: 'status', key: 'status', minWidth: 110,
      render: (v: WorkflowTaskStatus) => <Tag color={statusBadge(v)}>{t(`workflow.todo.status.${v}`)}</Tag>,
    },
    { title: t('workflow.todo.columns.createTime'), dataIndex: 'createTime', key: 'createTime', minWidth: 170, render: (v: string) => formatDate(v) },
    {
      title: t('workflow.todo.columns.actions'), key: 'actions', minWidth: 100, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          {/* 单入口「办理」（82 分控；CREATED/CLAIMED 均进审批详情 Drawer，操作区按状态分控） */}
          <Auth code={TASK_OPERATE_PERM}>
            <Button size="small" type="primary" ghost onClick={() => setDetailTask(record)}>
              {t('workflow.todo.actions.complete')}
            </Button>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<TaskView, TaskFilters>
        crud={crud}
        title={t('workflow.todo.segments.todo')}
        columns={columns}
        rowKey="id"
        reloadAriaLabel={t('workflow.todo.errors.reloadAria')}
        totalText={(total) => t('common.table.total', { total })}
        filterPanel={<TaskFilterPanel crud={crud} filterForm={filterForm} />}
      />
      <ApprovalDetailDrawer task={detailTask} onClose={closeDetail} onChanged={() => crud.reload()} />
    </>
  );
}

/** 已办子区段（done 只读视角：行内「查看」打开审批详情 Drawer——终态任务无操作按钮，AC-5）。 */
function MyDoneSection(): ReactElement {
  const { t } = useTranslation();
  const [filterForm] = Form.useForm<TaskFilterFormValues>();
  const [detailTask, setDetailTask] = useState<TaskView | null>(null);

  const crud = useCrud<TaskView, TaskFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      taskApi.done(page, size, {
        definitionKey: filters?.definitionKey || undefined,
        status: (filters?.status as WorkflowTaskStatus | undefined) ?? undefined,
      }, signal),
    filters: { definitionKey: undefined, status: undefined },
    errorMessage: t('workflow.todo.errors.loadFailed'),
  });

  const closeDetail = useCallback(() => setDetailTask(null), []);

  const columns: ColumnsType<TaskView> = [
    { title: t('workflow.todo.columns.nodeName'), dataIndex: 'nodeName', key: 'nodeName', minWidth: 150, fixed: 'left', render: (v: string | null, record) => (v || record.nodeId) },
    { title: t('workflow.todo.columns.definitionName'), dataIndex: 'definitionName', key: 'definitionName', minWidth: 150, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('workflow.todo.columns.businessKey'), dataIndex: 'businessKey', key: 'businessKey', minWidth: 170, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('workflow.todo.columns.initiator'), dataIndex: 'initiator', key: 'initiator', minWidth: 110, render: (v: string | null) => v ?? '—' },
    {
      title: t('workflow.todo.columns.status'), dataIndex: 'status', key: 'status', minWidth: 110,
      render: (v: WorkflowTaskStatus) => <Tag color={statusBadge(v)}>{t(`workflow.todo.status.${v}`)}</Tag>,
    },
    { title: t('workflow.todo.columns.createTime'), dataIndex: 'createTime', key: 'createTime', minWidth: 170, render: (v: string) => formatDate(v) },
    {
      title: t('workflow.todo.columns.actions'), key: 'actions', minWidth: 100, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          {/* 只读单入口「查看」（读面不需要 82 写分控；Drawer 终态自动只读） */}
          <Button size="small" onClick={() => setDetailTask(record)}>
            {t('workflow.todo.actions.view')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<TaskView, TaskFilters>
        crud={crud}
        title={t('workflow.todo.segments.done')}
        columns={columns}
        rowKey="id"
        reloadAriaLabel={t('workflow.todo.errors.reloadAria')}
        totalText={(total) => t('common.table.total', { total })}
        filterPanel={<TaskFilterPanel crud={crud} filterForm={filterForm} />}
      />
      <ApprovalDetailDrawer task={detailTask} onClose={closeDetail} onChanged={() => crud.reload()} />
    </>
  );
}

/** 我发起子区段（实例分页：definitionName/businessKey/status 投影列；行内「查看」复用实例详情 Drawer——AC-6）。 */
function MyInitiatedSection(): ReactElement {
  const { t } = useTranslation();
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const crud = useCrud<ProcessInstanceView, Record<string, never>>({
    fetchPage: ({ page, size, signal }) => instanceApi.initiated(page, size, signal),
    errorMessage: t('workflow.todo.errors.initiatedFailed'),
  });

  const closeDetail = useCallback(() => {
    setDetailOpen(false);
    setDetailId(null);
  }, []);

  const columns: ColumnsType<ProcessInstanceView> = [
    { title: t('workflow.instance.columns.id'), dataIndex: 'id', key: 'id', minWidth: 110, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('workflow.todo.columns.definitionName'), dataIndex: 'definitionName', key: 'definitionName', minWidth: 150, ellipsis: true, render: (v: string | null | undefined) => v ?? '—' },
    { title: t('workflow.instance.columns.businessKey'), dataIndex: 'businessKey', key: 'businessKey', minWidth: 170, ellipsis: true, render: (v: string | null) => v ?? '—' },
    {
      title: t('workflow.todo.columns.status'), dataIndex: 'status', key: 'status', minWidth: 110,
      render: (v: ProcessInstanceStatus) => <Tag>{t(`workflow.instance.status.${v}`)}</Tag>,
    },
    { title: t('workflow.todo.columns.startTime'), dataIndex: 'startTime', key: 'startTime', minWidth: 170, render: (v: string) => formatDate(v) },
    {
      title: t('workflow.todo.columns.actions'), key: 'actions', minWidth: 100, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Button
            size="small"
            onClick={() => {
              setDetailId(record.id);
              setDetailOpen(true);
            }}
          >
            {t('workflow.todo.actions.view')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<ProcessInstanceView, Record<string, never>>
        crud={crud}
        title={t('workflow.todo.segments.initiated')}
        columns={columns}
        rowKey="id"
        reloadAriaLabel={t('workflow.todo.errors.reloadAria')}
        totalText={(total) => t('common.table.total', { total })}
      />
      {/* 我发起「查看」→ 实例详情视角（复用 BPM-3 InstanceDetailDrawer readonly 三件套） */}
      <InstanceDetailDrawer
        instanceId={detailId}
        open={detailOpen}
        onClose={closeDetail}
        onChanged={() => crud.reload()}
      />
    </>
  );
}

/** 待办中心页壳（Segmented 三子区段切换：待办/已办/我发起；权限门禁=路由 81 workflow:task:list）。 */
export function TodoCenterPage(): ReactElement {
  const { t } = useTranslation();
  const [segment, setSegment] = useState<'todo' | 'done' | 'initiated'>('todo');
  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>{t('workflow.todo.title')}</Typography.Title>
      <Segmented
        options={[
          { value: 'todo', label: t('workflow.todo.segments.todo') },
          { value: 'done', label: t('workflow.todo.segments.done') },
          { value: 'initiated', label: t('workflow.todo.segments.initiated') },
        ]}
        value={segment}
        onChange={(value) => setSegment(value as 'todo' | 'done' | 'initiated')}
        style={{ marginBottom: 16 }}
      />
      {/* 操作面收编 Drawer 内 Auth 分控（82 fail-closed）；列表读面 81 由路由 PermissionGate 承担 */}
      {segment === 'todo' ? <MyTodosSection /> : segment === 'done' ? <MyDoneSection /> : <MyInitiatedSection />}
    </div>
  );
}
