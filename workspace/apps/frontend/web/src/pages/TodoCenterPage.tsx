import { useCallback, useState, type ReactElement } from 'react';
import { App, Button, DatePicker, Descriptions, Drawer, Form, Input, Modal, Segmented, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { FormInstance } from 'antd';
import {
  Auth, formatDate, instanceApi, leaveApi, systemErrorMessage, TONE_ANTD, taskApi, useCrud,
  type CrudController, type LeaveRequestView, type LeaveStatus,
  type ProcessInstanceStatus, type ProcessInstanceView,
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
 * REQ-20261124-213008 范式 F：头部 Button「发起请假」→ Modal（请假类型/事由/起止时间）
 * → 成功关弹窗并以返回 id 调 GET 详情打开详情抽屉（状态中文映射 + 流程实例号可空展示）。
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

/** 请假单状态 → 语义 Tone（APPLYING=muted/APPROVING=info/FILED=violet/DONE=success/REJECTED=danger）。 */
function leaveStatusTone(status: LeaveStatus): keyof typeof TONE_ANTD {
  switch (status) {
    case 'APPROVING': return 'info';
    case 'FILED': return 'violet';
    case 'DONE': return 'success';
    case 'REJECTED': return 'danger';
    default: return 'muted';
  }
}

/** 请假类型全集（wire 值；显示名经 workflow.leave.type，未登记值原样透出）。 */
const LEAVE_TYPE_OPTIONS = ['PERSONAL', 'ANNUAL', 'SICK'] as const;
type LeaveType = (typeof LEAVE_TYPE_OPTIONS)[number];

/** 请假类型 → i18n key 映射（未登记值回退 null=原样透出）。 */
const LEAVE_TYPE_LABEL_KEYS: Record<string, string> = {
  PERSONAL: 'workflow.leave.type.PERSONAL',
  ANNUAL: 'workflow.leave.type.ANNUAL',
  SICK: 'workflow.leave.type.SICK',
};

/** 请假发起表单值（range 提交时转 ISO-8601）。 */
interface LeaveApplyFormValues {
  leaveType?: LeaveType;
  reason?: string;
  range?: [Dayjs, Dayjs] | null;
}

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

/** 待办中心页壳（Segmented 三子区段切换：待办/已办/我发起；权限门禁=路由 81 workflow:task:list；
 *  头部「发起请假」入口 + 请假单详情抽屉（REQ-20261124-213008 范式 F；无新权限点——B 端用户令牌面）。 */
export function TodoCenterPage(): ReactElement {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [segment, setSegment] = useState<'todo' | 'done' | 'initiated'>('todo');

  // 发起请假（范式 F）：Modal 表单 + 提交；成功关 Modal 并以返回 id 调 GET 详情打开详情抽屉
  const [applyOpen, setApplyOpen] = useState(false);
  const [applySubmitting, setApplySubmitting] = useState(false);
  const [applyForm] = Form.useForm<LeaveApplyFormValues>();
  const [detailView, setDetailView] = useState<LeaveRequestView | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const openApply = () => {
    applyForm.resetFields();
    setApplyOpen(true);
  };

  /** 提交请假：businessKey=前端生成 'LEAVE-{uuid}'；range → ISO-8601；前端校验（必填/结束晚于开始）失败不发请求。 */
  const submitApply = async () => {
    // Modal onOk 以 `void submitApply()` 丢弃 Promise：validateFields 的校验 rejection 必须就地捕获，
    // 否则成为 Unhandled Rejection；失败时错误提示已由 Form rules 内联展示，直接 return 不发请求。
    const values = await applyForm.validateFields().catch(() => null);
    if (!values) {
      return;
    }
    const range = values.range ?? null;
    if (!values.leaveType || !range || !range[0] || !range[1]) {
      // 必填缺失拦截（validateFields 已拦截，此处兜底不发请求）
      return;
    }
    setApplySubmitting(true);
    try {
      const created = await leaveApi.apply({
        businessKey: `LEAVE-${crypto.randomUUID()}`,
        leaveType: values.leaveType,
        reason: values.reason?.trim() ? values.reason.trim() : null,
        startTime: range[0].toISOString(),
        endTime: range[1].toISOString(),
      });
      message.success(t('workflow.leave.submitted'));
      setApplyOpen(false);
      // AC-13：以返回 id 调 GET 详情打开详情抽屉
      try {
        setDetailView(await leaveApi.get(created.id));
        setDetailOpen(true);
      } catch {
        message.error(t('workflow.leave.errors.detailFailed'));
      }
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('workflow.leave.errors.submitFailed')));
    } finally {
      setApplySubmitting(false);
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16 }}>
        <Typography.Title level={4} style={{ marginTop: 0, marginBottom: 0 }}>{t('workflow.todo.title')}</Typography.Title>
        <Button type="primary" onClick={openApply}>{t('workflow.leave.apply')}</Button>
      </div>
      <Segmented
        options={[
          { value: 'todo', label: t('workflow.todo.segments.todo') },
          { value: 'done', label: t('workflow.todo.segments.done') },
          { value: 'initiated', label: t('workflow.todo.segments.initiated') },
        ]}
        value={segment}
        onChange={(value) => setSegment(value as 'todo' | 'done' | 'initiated')}
        style={{ marginBottom: 16, marginTop: 12 }}
      />
      {/* 操作面收编 Drawer 内 Auth 分控（82 fail-closed）；列表读面 81 由路由 PermissionGate 承担 */}
      {segment === 'todo' ? <MyTodosSection /> : segment === 'done' ? <MyDoneSection /> : <MyInitiatedSection />}

      <Modal
        open={applyOpen}
        title={t('workflow.leave.applyTitle')}
        onCancel={() => { setApplyOpen(false); applyForm.resetFields(); }}
        onOk={() => void submitApply()}
        confirmLoading={applySubmitting}
        destroyOnClose
      >
        <Form form={applyForm} layout="vertical">
          <Form.Item
            name="leaveType"
            label={t('workflow.leave.form.leaveType')}
            rules={[{ required: true, message: t('workflow.leave.form.leaveTypeRequired') }]}
          >
            <Select
              virtual={false}
              placeholder={t('common.validation.optional')}
              options={LEAVE_TYPE_OPTIONS.map((value) => ({ value, label: t(`workflow.leave.type.${value}`) }))}
            />
          </Form.Item>
          <Form.Item name="reason" label={t('workflow.leave.form.reason')}>
            <Input.TextArea rows={3} placeholder={t('workflow.leave.form.reasonPlaceholder')} />
          </Form.Item>
          <Form.Item
            name="range"
            label={t('workflow.leave.form.range')}
            rules={[
              { required: true, message: t('workflow.leave.form.rangeRequired') },
              {
                validator: (_, value: [Dayjs, Dayjs] | null | undefined) => {
                  if (!value || !value[0] || !value[1] || value[1].isAfter(value[0])) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error(t('workflow.leave.form.rangeInvalid')));
                },
              },
            ]}
          >
            <DatePicker.RangePicker showTime style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        title={t('workflow.leave.detailTitle')}
        width={480}
        destroyOnHidden
      >
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label={t('workflow.leave.detail.id')}><Text code>{detailView?.id}</Text></Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.userName')}>{detailView?.userName}</Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.leaveType')}>
            {detailView ? (LEAVE_TYPE_LABEL_KEYS[detailView.leaveType] ? t(LEAVE_TYPE_LABEL_KEYS[detailView.leaveType]) : detailView.leaveType) : null}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.reason')}>{detailView?.reason ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.startTime')}>{detailView ? formatDate(detailView.startTime) : null}</Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.endTime')}>{detailView ? formatDate(detailView.endTime) : null}</Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.status')}>
            {detailView ? (
              <Tag color={TONE_ANTD[leaveStatusTone(detailView.status)]}>{t(`workflow.leave.status.${detailView.status}`)}</Tag>
            ) : null}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.instanceId')}>
            {detailView?.instanceId ? <Text code>{detailView.instanceId}</Text> : '—'}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflow.leave.detail.businessKey')}>
            {detailView ? <Text code>{detailView.businessKey}</Text> : null}
          </Descriptions.Item>
        </Descriptions>
      </Drawer>
    </div>
  );
}
