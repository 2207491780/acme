import { useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import {
  App, Button, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Select,
  Space, Spin, Tag, Timeline, Typography,
} from 'antd';
import type { Graph } from '@antv/x6';
import { useTranslation } from 'react-i18next';
import {
  Auth, formatDate, instanceApi, resolveCanvasPalette, resolveThemeMode,
  systemErrorMessage, taskApi, TONE_ANTD, workflowApi,
  type HistoryActivityView, type HistoryAction, type ProcessInstanceView, type TaskView,
  type WorkflowTaskStatus,
} from '@acme/core';
import { useStore } from '../../store/useStore';
import { useSystemPrefersDark } from '../../hooks/useSystemPrefersDark';
import { applyCanvasTheme, createBpmnCanvas, renderReadOnlyBpmn } from './canvasFactory';
import { nodeSummary } from './designerModel';
import { historyActionColor } from './InstanceMonitorTab';

const { Text } = Typography;

/**
 * 审批详情 Drawer（REQ-20261122-150750 契约②，width ~880 五块信息架构）：
 * 任务信息 / 审批操作区（claim/unclaim/complete/reject/transfer/delegate 按 status 与 82 分控收编）/
 * 实例上下文摘要 / 历史时间线（action 分色，复用 BPM-3 实例详情口径）/ 流程图（readOnly 三件套）。
 * - 动态表单：antd Form.List 键值对（可增删行）提交 {form:{k:v}}+comment 独立（契约③，AC-2）；
 * - 驳回候选：history 过滤 action=ENTER 且 detail.delegate 为空+nodeId 去重（保留首条）+含 StartEvent（契约⑤，AC-3）；
 * - delegate 仅 CLAIMED 可见（契约⑥，AC-4）；done/终态任务只读（无操作按钮，AC-5）；
 * - 流程图 activeNodeIds=实例活跃节点+本任务 nodeId 强调、rejectEdgeIds=历史 REJECT_EDGE 边标红（契约②）。
 */

const TASK_OPERATE_PERM = 'workflow:task:operate';

/** 任务状态 → AntD 预设名（UI-SPEC §2.2 语义 Tone 口径，TodoCenterPage 既有 taskStatusTone 同款）。 */
function taskStatusBadge(status: WorkflowTaskStatus): string {
  switch (status) {
    case 'CLAIMED': return TONE_ANTD.info;
    case 'COMPLETED': return TONE_ANTD.success;
    case 'REJECTED': return TONE_ANTD.danger;
    default: return TONE_ANTD.muted;
  }
}

/** 历史动作 → 时间线分色（BPM-3 实例详情口径复用；TONES 令牌单源）。 */
const timelineColor = (action: HistoryAction): string => historyActionColor(action);

/** 委托落库记录判定（契约⑤）：DelegateTaskCommand 落 action=ENTER+detail.delegate（owner 记录），
 *  detail 为 JSON 字符串且含非空 delegate 字段即污染记录；detail 非 JSON（纯文本）视为非委托记录。 */
function isDelegateEnterRecord(row: HistoryActivityView): boolean {
  if (!row.detail) return false;
  try {
    const parsed: unknown = JSON.parse(row.detail);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const delegate = (parsed as Record<string, unknown>).delegate;
      return typeof delegate === 'string' && delegate !== '';
    }
  } catch {
    // 非 JSON detail 非委托记录
  }
  return false;
}

/** 驳回目标候选（契约⑤，AC-3）：history 过滤 action=ENTER 且 detail.delegate 为空（剔除委托污染）
 *  + 按 nodeId 去重（保留首条）+ 含 StartEvent（后端合法目标）。 */
export function rejectCandidatesOf(history: HistoryActivityView[]): HistoryActivityView[] {
  const seen = new Set<string>();
  const candidates: HistoryActivityView[] = [];
  for (const row of history) {
    if (row.action !== 'ENTER' || isDelegateEnterRecord(row) || seen.has(row.nodeId)) continue;
    seen.add(row.nodeId);
    candidates.push(row);
  }
  return candidates;
}

/** 动态表单行值（键值对自由编辑）。 */
interface DynamicFormItem {
  key?: string;
  value?: string;
}

/** 转办/委托 Modal（AssigneeModal 双文案：按动作分标题，i18n transferTitle/delegateTitle 双键）。 */
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
 * 审批详情 Drawer：五块装配 + 状态分控操作区。
 * 数据面：打开即取实例详情+历史（fail-soft），定义 XML 就绪后 readOnly 装配流程图。
 */
export function ApprovalDetailDrawer({ task, onClose, onChanged }: {
  task: TaskView | null;
  onClose: () => void;
  onChanged: () => void;
}): ReactElement {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const preferences = useStore((s) => s.state.settings.preferences);
  const systemPrefersDark = useSystemPrefersDark();
  const resolvedMode = resolveThemeMode(preferences.mode, systemPrefersDark);
  const canvas = useMemo(() => resolveCanvasPalette(resolvedMode), [resolvedMode]);
  const dark = resolvedMode === 'dark';

  const open = task !== null;
  const [instance, setInstance] = useState<ProcessInstanceView | null>(null);
  const [history, setHistory] = useState<HistoryActivityView[]>([]);
  const [rejectCandidates, setRejectCandidates] = useState<HistoryActivityView[]>([]);
  const [bpmnXml, setBpmnXml] = useState('');
  const [layoutJson, setLayoutJson] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [assigneeMode, setAssigneeMode] = useState<'transfer' | 'delegate' | null>(null);

  const [completeForm] = Form.useForm<{ items?: DynamicFormItem[]; comment?: string }>();
  const [rejectForm] = Form.useForm<{ comment?: string; targetNodeId?: string }>();

  const containerRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<Graph | null>(null);

  // 打开即取实例详情+历史（契约⑤修正面：历史供时间线/驳回候选/边标红三消费方）
  useEffect(() => {
    if (!open || !task) {
      setInstance(null);
      setHistory([]);
      setRejectCandidates([]);
      setBpmnXml('');
      setLayoutJson(null);
      completeForm.resetFields();
      rejectForm.resetFields();
      return;
    }
    let cancelled = false;
    setLoading(true);
    Promise.all([
      instanceApi.get(task.instanceId),
      instanceApi.history(task.instanceId).catch(() => [] as HistoryActivityView[]),
    ])
      .then(([view, historyRows]) => {
        if (cancelled) return;
        setInstance(view);
        setHistory(historyRows);
        setRejectCandidates(rejectCandidatesOf(historyRows));
      })
      .catch((ex: unknown) => {
        if (!cancelled) message.error(systemErrorMessage(ex, t('workflow.todo.errors.historyFailed')));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, task?.id]);

  // 定义 XML（流程图渲染源；fail-soft：缺源=流程图空态，其余四块照常）
  useEffect(() => {
    const definitionId = instance?.definitionId;
    if (!open || !definitionId) return;
    let cancelled = false;
    workflowApi.get(definitionId)
      .then((view) => {
        if (cancelled) return;
        setBpmnXml(view?.bpmnXml ?? '');
        setLayoutJson(view?.layoutJson ?? null);
      })
      .catch(() => {
        // 流程图缺源=非关键面
      });
    return () => {
      cancelled = true;
    };
  }, [open, instance?.definitionId]);

  // readOnly 流程图：activeNodeIds=实例活跃节点+本任务 nodeId 强调；rejectEdgeIds=历史 REJECT_EDGE
  const activeNodeIds = useMemo(() => {
    const ids = new Set((instance?.activeNodes ?? []).map((node) => node.nodeId));
    if (task) ids.add(task.nodeId);
    return ids;
  }, [instance, task]);
  const rejectEdgeIds = useMemo(
    () => new Set(history.filter((item) => item.action === 'REJECT_EDGE').map((item) => item.nodeId)),
    [history],
  );
  useEffect(() => {
    if (!open || !bpmnXml || !containerRef.current) {
      graphRef.current?.dispose();
      graphRef.current = null;
      return undefined;
    }
    const graph = createBpmnCanvas({ container: containerRef.current, palette: canvas, readOnly: true });
    graphRef.current = graph;
    renderReadOnlyBpmn(graph, { bpmnXml, layoutJson, activeNodeIds, rejectEdgeIds }, canvas, dark, {
      nameOf: (node) => node.name || t(`workflow.designer.palette.items.${node.type}`),
      summaryOf: nodeSummary,
    });
    return () => {
      graph.dispose();
      graphRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, bpmnXml, layoutJson, activeNodeIds, rejectEdgeIds]);

  // 主题变化 → 只读画布网格/背景跟随
  useEffect(() => {
    const graph = graphRef.current;
    if (graph) applyCanvasTheme(graph, canvas);
  }, [canvas]);

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
    // 契约③：自由键值动态表单提交 {form:{k:v}}，comment 独立字段；空表单仅 comment 合法（AC-2）
    const form: Record<string, unknown> = {};
    for (const item of values.items ?? []) {
      const key = (item?.key ?? '').trim();
      if (key) form[key] = item?.value ?? '';
    }
    const comment = values.comment?.trim() || undefined;
    await runTaskAction(
      () => taskApi.complete(task.id, {
        ...(Object.keys(form).length > 0 ? { form } : {}),
        ...(comment ? { comment } : {}),
      }),
      'workflow.todo.toast.completed',
    );
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

  const closeAssignee = () => setAssigneeMode(null);
  const isClaimed = task?.status === 'CLAIMED';
  const isTodoStatus = task?.status === 'CREATED' || task?.status === 'CLAIMED';

  return (
    <Drawer open={open} onClose={onClose} title={t('workflow.todo.drawer.title')} width={880} destroyOnHidden>
      {task ? (
        <Spin spinning={loading}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
            {/* 一、任务信息 */}
            <section>
              <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.task')}</Typography.Title>
              <Descriptions size="small" column={2} bordered>
                <Descriptions.Item label={t('workflow.todo.columns.nodeName')}>{task.nodeName || task.nodeId}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.status')}>
                  <Tag color={taskStatusBadge(task.status)}>{t(`workflow.todo.status.${task.status}`)}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.definitionName')}>{task.definitionName ?? task.definitionKey ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.businessKey')}>{task.businessKey ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.initiator')}>{task.initiator ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.assignee')}>{task.assignee ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.instance.drawer.claimTime')}>{task.claimTime ? formatDate(task.claimTime) : '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.createTime')}>{formatDate(task.createTime)}</Descriptions.Item>
              </Descriptions>
            </section>

            {/* 二、审批操作区（按 status 与 82 分控：CREATED 仅 claim；CLAIMED 显全部；终态只读提示） */}
            <section>
              <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.approval')}</Typography.Title>
              {isTodoStatus ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                  <Space size={8} wrap>
                    {task.status === 'CREATED' ? (
                      <Auth code={TASK_OPERATE_PERM}>
                        <Popconfirm title={t('workflow.todo.confirm.claim')} onConfirm={() => void runTaskAction(() => taskApi.claim(task.id), 'workflow.todo.toast.claimed')}>
                          <Button type="primary" loading={submitting}>{t('workflow.todo.actions.claim')}</Button>
                        </Popconfirm>
                      </Auth>
                    ) : null}
                    {isClaimed ? (
                      <>
                        <Auth code={TASK_OPERATE_PERM}>
                          <Popconfirm title={t('workflow.todo.confirm.unclaim')} onConfirm={() => void runTaskAction(() => taskApi.unclaim(task.id), 'workflow.todo.toast.unclaimed')}>
                            <Button loading={submitting}>{t('workflow.todo.actions.unclaim')}</Button>
                          </Popconfirm>
                        </Auth>
                        {/* 契约⑥：delegate 仅 CLAIMED 可见（转办 CREATED/CLAIMED 均可，AC-4 口径 CLAIMED 分支内） */}
                        <Auth code={TASK_OPERATE_PERM}>
                          <Button onClick={() => setAssigneeMode('transfer')}>{t('workflow.todo.actions.transfer')}</Button>
                          <Button onClick={() => setAssigneeMode('delegate')}>{t('workflow.todo.actions.delegate')}</Button>
                        </Auth>
                      </>
                    ) : null}
                  </Space>
                  {isClaimed ? (
                    <>
                      {/* 办理：动态键值表单 + comment 独立（契约③） */}
                      <Form form={completeForm} layout="vertical">
                        <Form.List name="items" initialValue={[]}>
                          {(fields, { add, remove }) => (
                            <>
                              {fields.map((field) => (
                                <Space key={field.key} align="baseline" style={{ display: 'flex' }}>
                                  <Form.Item
                                    name={[field.name, 'key']}
                                    label={t('workflow.todo.drawer.formKey')}
                                    rules={[{ required: true, message: t('workflow.todo.drawer.formKeyPlaceholder') }]}
                                  >
                                    <Input placeholder={t('workflow.todo.drawer.formKeyPlaceholder')} autoComplete="off" />
                                  </Form.Item>
                                  <Form.Item name={[field.name, 'value']} label={t('workflow.todo.drawer.formValue')}>
                                    <Input placeholder={t('workflow.todo.drawer.formValuePlaceholder')} autoComplete="off" />
                                  </Form.Item>
                                  <Button type="text" danger aria-label={t('workflow.todo.drawer.removeFormItem')} onClick={() => remove(field.name)}>
                                    {t('workflow.todo.drawer.removeFormItem')}
                                  </Button>
                                </Space>
                              ))}
                              <Button type="dashed" onClick={() => add()}>
                                {t('workflow.todo.drawer.addFormItem')}
                              </Button>
                            </>
                          )}
                        </Form.List>
                        <Form.Item name="comment" label={t('workflow.todo.drawer.comment')} style={{ marginTop: 16 }}>
                          <Input.TextArea rows={3} placeholder={t('workflow.todo.drawer.commentPlaceholder')} />
                        </Form.Item>
                        <Auth code={TASK_OPERATE_PERM}>
                          <Button type="primary" loading={submitting} onClick={() => void submitComplete()}>
                            {t('workflow.todo.drawer.submitComplete')}
                          </Button>
                        </Auth>
                      </Form>
                      {/* 驳回：候选=契约⑤修正口径 */}
                      <Form form={rejectForm} layout="vertical">
                        <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.reject')}</Typography.Title>
                        <Form.Item name="targetNodeId" label={t('workflow.todo.drawer.rejectTarget')}>
                          <Select
                            virtual={false}
                            allowClear
                            placeholder={t('workflow.todo.drawer.rejectTargetPlaceholder')}
                            options={rejectCandidates.map((row) => ({ value: row.nodeId, label: row.nodeId }))}
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
                    </>
                  ) : null}
                </div>
              ) : (
                <Text type="secondary">{t('workflow.todo.drawer.readonlyHint')}</Text>
              )}
            </section>

            {/* 三、实例上下文摘要 */}
            <section>
              <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.context')}</Typography.Title>
              <Descriptions size="small" column={2} bordered>
                <Descriptions.Item label={t('workflow.todo.columns.definitionName')}>{instance?.definitionName ?? task.definitionName ?? task.definitionKey ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.businessKey')}>{instance?.businessKey ?? task.businessKey ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.initiator')}>{instance?.initiator ?? task.initiator ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('workflow.todo.columns.startTime')}>{instance ? formatDate(instance.startTime) : '—'}</Descriptions.Item>
              </Descriptions>
            </section>

            {/* 四、历史时间线（action 分色，复用 BPM-3 口径） */}
            <section>
              <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.history')}</Typography.Title>
              {history.length === 0 ? (
                <Text type="secondary">{t('workflow.instance.drawer.noHistory')}</Text>
              ) : (
                <Timeline
                  items={history.map((item) => ({
                    color: timelineColor(item.action),
                    children: (
                      <Space size={6} wrap>
                        <Tag>{t(`workflow.instance.historyAction.${item.action}`)}</Tag>
                        <Text code style={{ fontSize: 12 }}>{item.nodeId}</Text>
                        {item.assignee ? <Text type="secondary">{item.assignee}</Text> : null}
                        {item.startAt ? <Text type="secondary">{formatDate(item.startAt)}</Text> : null}
                        {item.detail ? <Text type="secondary">{item.detail}</Text> : null}
                      </Space>
                    ),
                  }))}
                />
              )}
            </section>

            {/* 五、流程图（readOnly 三件套：activeNodeIds 高亮+本任务强调、REJECT_EDGE 边标红） */}
            <section>
              <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.todo.drawer.diagram')}</Typography.Title>
              {bpmnXml ? (
                <div
                  ref={containerRef}
                  data-testid="bpmn-approval-canvas"
                  style={{ height: 360, border: '1px solid var(--border)', borderRadius: 8, overflow: 'hidden' }}
                />
              ) : (
                <Text type="secondary">{t('workflow.instance.drawer.diagramFailed')}</Text>
              )}
            </section>
          </div>
        </Spin>
      ) : null}
      <AssigneeModal
        open={assigneeMode !== null}
        mode={assigneeMode}
        taskId={task?.id ?? null}
        onClose={closeAssignee}
        onDone={() => {
          onChanged();
          onClose();
        }}
      />
    </Drawer>
  );
}
