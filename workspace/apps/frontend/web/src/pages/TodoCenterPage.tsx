import { useCallback, useEffect, useState, type ReactElement } from 'react';
import {
  App, Button, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Segmented, Select,
  Space, Tag, Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  Auth, formatDate, instanceApi, systemErrorMessage, TONE_ANTD, taskApi, useCrud,
  type HistoryActivityView, type ProcessInstanceStatus, type ProcessInstanceView,
  type TaskView, type WorkflowTaskStatus,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';
import { FilterPanel } from '../components/FilterPanel';

const { Text } = Typography;

/**
 * 待办中心页（REQ-20261122-102730 契约②③，/workflow/todos 独立路由，JobsPage 页模式）：
 * Segmented 子区段（我的待办/我发起）+ 分页列表（definitionName/businessKey/initiator 投影列）
 * + 行操作 claim/unclaim/transfer/delegate（Popconfirm/Modal，Auth 分控 82）
 * + 任务详情 Drawer（complete 表单提交 + reject targetNodeId 选择）。
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

/** 转办/委托 Modal（assignee 单字段表单；delegate 委托链单层）。 */
function AssigneeModal({ open, mode, taskId, onClose, onDone }: {
  open: boolean;
  mode: 'transfer' | 'delegate' | null;
  taskId: string | null;
  onClose: () => void;
  onDone: () => void;
}): ReactElement {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<{ assignee: string }>();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  const submit = async () => {
    if (!taskId) return;
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (mode === 'delegate') {
        await taskApi.delegate(taskId, { assignee: values.assignee.trim() });
        message.success(t('workflow.todo.toast.delegated'));
      } else {
        await taskApi.transfer(taskId, { assignee: values.assignee.trim() });
        message.success(t('workflow.todo.toast.transferred'));
      }
      onClose();
      onDone();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('workflow.todo.toast.actionFailed')));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={mode === 'delegate' ? t('workflow.todo.drawer.delegateTitle') : t('workflow.todo.drawer.transferTitle')}
      destroyOnHidden
      footer={[
        <Button key="cancel" onClick={onClose}>{t('common.actions.cancel')}</Button>,
        <Button key="submit" type="primary" loading={submitting} onClick={() => void submit()}>
          {t('workflow.todo.drawer.submitAssignee')}
        </Button>,
      ]}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="assignee"
          label={t('workflow.todo.drawer.assignee')}
          rules={[{ required: true, message: t('common.validation.optional') }]}
        >
          <Input allowClear placeholder={t('workflow.todo.drawer.assigneePlaceholder')} autoComplete="off" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/**
 * 任务详情 Drawer（契约②）：任务信息 Descriptions + complete 表单（comment）
 * + reject（targetNodeId 选择：候选=实例历史 ENTER 节点，驳回到指定节点）。
 */
function TaskDetailDrawer({ task, onClose, onChanged }: {
  task: TaskView | null;
  onClose: () => void;
  onChanged: () => void;
}): ReactElement {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const open = task !== null;
  const [submitting, setSubmitting] = useState(false);
  const [rejectTargets, setRejectTargets] = useState<HistoryActivityView[]>([]);
  const [completeForm] = Form.useForm<{ comment?: string }>();
  const [rejectForm] = Form.useForm<{ comment?: string; targetNodeId?: string }>();

  // 驳回目标候选=实例历史节点（ENTER 配对出现过的节点即可作为回流目标）
  useEffect(() => {
    if (!open || !task) {
      setRejectTargets([]);
      return;
    }
    completeForm.resetFields();
    rejectForm.resetFields();
    let cancelled = false;
    instanceApi.history(task.instanceId)
      .then((rows) => {
        if (cancelled) return;
        const seen = new Set<string>();
        setRejectTargets(rows.filter((row) => {
          if (row.action !== 'ENTER' || seen.has(row.nodeId)) return false;
          seen.add(row.nodeId);
          return true;
        }));
      })
      .catch(() => {
        if (!cancelled) setRejectTargets([]);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, task?.id]);

  const runTaskAction = async (action: () => Promise<void>, toastKey: string) => {
    if (!task) return;
    setSubmitting(true);
    try {
      await action();
      message.success(t(toastKey));
      onChanged();
      onClose();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('workflow.todo.toast.actionFailed')));
    } finally {
      setSubmitting(false);
    }
  };

  const submitComplete = async () => {
    if (!task) return;
    const values = await completeForm.validateFields().catch(() => null);
    if (!values) return;
    await runTaskAction(() => taskApi.complete(task.id, { comment: values.comment?.trim() || undefined }), 'workflow.todo.toast.completed');
  };

  const submitReject = async () => {
    if (!task) return;
    const values = await rejectForm.validateFields().catch(() => null);
    if (!values) return;
    await runTaskAction(
      () => taskApi.reject(task.id, { targetNodeId: values.targetNodeId || null, comment: values.comment?.trim() || null }),
      'workflow.todo.toast.rejected',
    );
  };

  return (
    <Drawer open={open} onClose={onClose} title={t('workflow.todo.drawer.title')} width={640} destroyOnHidden>
      {task ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
          {/* 任务信息 */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.task')}</Typography.Title>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label={t('workflow.todo.columns.nodeName')}>{task.nodeName || task.nodeId}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.todo.columns.status')}>
                <Tag color={statusBadge(task.status)}>{t(`workflow.todo.status.${task.status}`)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.todo.columns.definitionName')}>{task.definitionName ?? task.definitionKey ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.todo.columns.businessKey')}>{task.businessKey ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.todo.columns.initiator')}>{task.initiator ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.todo.columns.assignee')}>{task.assignee ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.instance.drawer.claimTime')}>{task.claimTime ? formatDate(task.claimTime) : '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.todo.columns.createTime')}>{formatDate(task.createTime)}</Descriptions.Item>
            </Descriptions>
          </section>

          {/* 办理（complete 表单） */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.form')}</Typography.Title>
            <Form form={completeForm} layout="vertical">
              <Form.Item name="comment" label={t('workflow.todo.drawer.comment')}>
                <Input.TextArea rows={3} placeholder={t('workflow.todo.drawer.commentPlaceholder')} />
              </Form.Item>
              <Auth code={TASK_OPERATE_PERM}>
                <Button type="primary" loading={submitting} onClick={() => void submitComplete()}>
                  {t('workflow.todo.drawer.submitComplete')}
                </Button>
              </Auth>
            </Form>
          </section>

          {/* 驳回（targetNodeId 选择 + comment） */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.reject')}</Typography.Title>
            <Form form={rejectForm} layout="vertical">
              <Form.Item name="targetNodeId" label={t('workflow.todo.drawer.rejectTarget')}>
                <Select
                  virtual={false}
                  allowClear
                  placeholder={t('workflow.todo.drawer.rejectTargetPlaceholder')}
                  options={rejectTargets.map((row) => ({ value: row.nodeId, label: row.nodeId }))}
                  notFoundContent={t('workflow.todo.drawer.noRejectTargets')}
                />
              </Form.Item>
              <Form.Item name="comment" label={t('workflow.todo.drawer.rejectComment')}>
                <Input.TextArea rows={2} placeholder={t('workflow.todo.drawer.commentPlaceholder')} />
              </Form.Item>
              <Auth code={TASK_OPERATE_PERM}>
                <Button danger loading={submitting} onClick={() => void submitReject()}>
                  {t('workflow.todo.drawer.submitReject')}
                </Button>
              </Auth>
            </Form>
          </section>
        </div>
      ) : null}
    </Drawer>
  );
}

/** 待办筛选表单值。 */
interface TaskFilterFormValues {
  definitionKey?: string;
  status?: WorkflowTaskStatus;
}

type TaskFilters = { definitionKey?: string; status?: string };

/** 我的待办子区段（契约⑤投影列：definitionName/businessKey/initiator + claim/unclaim/办理/转办/委托分控 82）。 */
function MyTodosSection(): ReactElement {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [filterForm] = Form.useForm<TaskFilterFormValues>();
  const [detailTask, setDetailTask] = useState<TaskView | null>(null);
  const [assigneeMode, setAssigneeMode] = useState<'transfer' | 'delegate' | null>(null);
  const [assigneeTask, setAssigneeTask] = useState<TaskView | null>(null);

  const crud = useCrud<TaskView, TaskFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      taskApi.todo(page, size, {
        definitionKey: filters?.definitionKey || undefined,
        status: (filters?.status as WorkflowTaskStatus | undefined) ?? undefined,
      }, signal),
    filters: { definitionKey: undefined, status: undefined },
    errorMessage: t('workflow.todo.errors.loadFailed'),
  });

  const runAction = async (action: () => Promise<void>, toastKey: string) => {
    try {
      await action();
      message.success(t(toastKey));
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('workflow.todo.toast.actionFailed')));
    }
  };

  const closeAssignee = useCallback(() => {
    setAssigneeMode(null);
    setAssigneeTask(null);
  }, []);

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
      title: t('workflow.todo.columns.actions'), key: 'actions', minWidth: 300, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          {/* 操作面按状态切换（AC-6：CREATED→claim；CLAIMED→unclaim/complete 可见） */}
          {record.status === 'CREATED' ? (
            <Auth code={TASK_OPERATE_PERM}>
              <Popconfirm title={t('workflow.todo.confirm.claim')} onConfirm={() => void runAction(() => taskApi.claim(record.id), 'workflow.todo.toast.claimed')}>
                <Button size="small">{t('workflow.todo.actions.claim')}</Button>
              </Popconfirm>
            </Auth>
          ) : null}
          {record.status === 'CLAIMED' ? (
            <Auth code={TASK_OPERATE_PERM}>
              <Popconfirm title={t('workflow.todo.confirm.unclaim')} onConfirm={() => void runAction(() => taskApi.unclaim(record.id), 'workflow.todo.toast.unclaimed')}>
                <Button size="small">{t('workflow.todo.actions.unclaim')}</Button>
              </Popconfirm>
            </Auth>
          ) : null}
          <Auth code={TASK_OPERATE_PERM}>
            <Button size="small" type="primary" ghost onClick={() => setDetailTask(record)}>
              {t('workflow.todo.actions.complete')}
            </Button>
            <Button size="small" onClick={() => { setAssigneeMode('transfer'); setAssigneeTask(record); }}>
              {t('workflow.todo.actions.transfer')}
            </Button>
            <Button size="small" onClick={() => { setAssigneeMode('delegate'); setAssigneeTask(record); }}>
              {t('workflow.todo.actions.delegate')}
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
        filterPanel={
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
        }
      />
      <TaskDetailDrawer
        task={detailTask}
        onClose={() => setDetailTask(null)}
        onChanged={() => crud.reload()}
      />
      <AssigneeModal
        open={assigneeMode !== null}
        mode={assigneeMode}
        taskId={assigneeTask?.id ?? null}
        onClose={closeAssignee}
        onDone={() => crud.reload()}
      />
    </>
  );
}

/** 我发起子区段（实例分页：definitionName/businessKey/status 投影列；只读跟踪面）。 */
function MyInitiatedSection(): ReactElement {
  const { t } = useTranslation();
  const crud = useCrud<ProcessInstanceView, Record<string, never>>({
    fetchPage: ({ page, size, signal }) => instanceApi.initiated(page, size, signal),
    errorMessage: t('workflow.todo.errors.initiatedFailed'),
  });

  const columns: ColumnsType<ProcessInstanceView> = [
    { title: t('workflow.instance.columns.id'), dataIndex: 'id', key: 'id', minWidth: 110, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('workflow.todo.columns.definitionName'), dataIndex: 'definitionName', key: 'definitionName', minWidth: 150, ellipsis: true, render: (v: string | null | undefined) => v ?? '—' },
    { title: t('workflow.instance.columns.businessKey'), dataIndex: 'businessKey', key: 'businessKey', minWidth: 170, ellipsis: true, render: (v: string | null) => v ?? '—' },
    {
      title: t('workflow.todo.columns.status'), dataIndex: 'status', key: 'status', minWidth: 110,
      render: (v: ProcessInstanceStatus) => <Tag>{t(`workflow.instance.status.${v}`)}</Tag>,
    },
    { title: t('workflow.todo.columns.startTime'), dataIndex: 'startTime', key: 'startTime', minWidth: 170, render: (v: string) => formatDate(v) },
  ];

  return (
    <CrudTable<ProcessInstanceView, Record<string, never>>
      crud={crud}
      title={t('workflow.todo.segments.initiated')}
      columns={columns}
      reloadAriaLabel={t('workflow.todo.errors.reloadAria')}
      totalText={(total) => t('common.table.total', { total })}
    />
  );
}

/** 待办中心页壳（Segmented 子区段切换；权限门禁=路由 81 workflow:task:list）。 */
export function TodoCenterPage(): ReactElement {
  const { t } = useTranslation();
  const [segment, setSegment] = useState<'todo' | 'initiated'>('todo');
  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>{t('workflow.todo.title')}</Typography.Title>
      <Segmented
        options={[
          { value: 'todo', label: t('workflow.todo.segments.todo') },
          { value: 'initiated', label: t('workflow.todo.segments.initiated') },
        ]}
        value={segment}
        onChange={(value) => setSegment(value as 'todo' | 'initiated')}
        style={{ marginBottom: 16 }}
      />
      {/* 操作面显隐由行内 Auth 分控（82 fail-closed）；列表读面 81 由路由 PermissionGate 承担 */}
      {segment === 'todo' ? <MyTodosSection /> : <MyInitiatedSection />}
    </div>
  );
}
