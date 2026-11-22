import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import { App, Button, Descriptions, Drawer, Form, Input, Popconfirm, Select, Space, Spin, Tag, Timeline, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import type { Graph } from '@antv/x6';
import {
  Auth, formatDate, instanceApi, resolveCanvasPalette, resolveThemeMode, statusColor,
  systemErrorMessage, TONES, TONE_ANTD, useCrud, workflowApi,
  type HistoryAction, type HistoryActivityView, type ProcessInstanceStatus,
  type ProcessInstanceView,
} from '@acme/core';
import { CrudTable } from '../../components/CrudTable';
import { FilterPanel } from '../../components/FilterPanel';
import { useStore } from '../../store/useStore';
import { useSystemPrefersDark } from '../../hooks/useSystemPrefersDark';
import { applyCanvasTheme, createBpmnCanvas, renderReadOnlyBpmn } from './canvasFactory';
import { nodeSummary } from './designerModel';

const { Text } = Typography;

/**
 * 实例监控 Tab（REQ-20261122-102730 契约②④⑦）：
 * 实例分页列表（definitionKey/status/businessKey 过滤）+ 状态徽标
 * （RUNNING=info/SUSPENDED=warning/COMPLETED=success/TERMINATED=muted，走 TONE_ANTD）
 * + 行操作挂起/终止/恢复（Popconfirm，Auth 分控 80）+ 详情 Drawer（五块信息架构）。
 */

/** 实例状态 → 语义 Tone（UI-SPEC §2.2 契约②口径）。 */
function instanceStatusTone(status: ProcessInstanceStatus): keyof typeof TONE_ANTD {
  switch (status) {
    case 'RUNNING': return 'info';
    case 'SUSPENDED': return 'warning';
    case 'COMPLETED': return 'success';
    default: return 'muted';
  }
}

const INSTANCE_STATUSES: ProcessInstanceStatus[] = ['RUNNING', 'SUSPENDED', 'COMPLETED', 'TERMINATED'];

/** 实例状态 → AntD 预设名（单元格文本取状态域字面量）。 */
const statusBadge = (status: ProcessInstanceStatus): string => TONE_ANTD[instanceStatusTone(status)];

/** 历史动作 → 时间线分色（TONES 令牌单源：ENTER=info/LEAVE=success/REJECT_EDGE=danger/TIMER_FIRE=warning）。 */
function historyActionColor(action: HistoryAction): string {
  switch (action) {
    case 'ENTER': return TONES.info;
    case 'LEAVE': return TONES.success;
    case 'REJECT_EDGE': return TONES.danger;
    default: return TONES.warning;
  }
}

/** 实例筛选表单值（查询型提交整组替换）。 */
interface InstanceFilterFormValues {
  definitionKey?: string;
  status?: ProcessInstanceStatus;
  businessKey?: string;
}

type InstanceFilters = { definitionKey?: string; status?: string; businessKey?: string };

const INSTANCE_MANAGE_PERM = 'workflow:instance:manage';

/**
 * 实例详情 Drawer（契约⑦，width ~880 五块信息架构）：
 * 摘要 / 活跃节点+任务树 / 变量摘要 / 历史时间线（action 分色）/
 * 流程图（readOnly 渲染定义 XML + activeNodes running 高亮 + REJECT_EDGE 边标红）。
 * 操作按钮按状态分控：RUNNING→挂起/终止；SUSPENDED→恢复（均 Auth 80）。
 */
function InstanceDetailDrawer({ instanceId, open, onClose, onChanged }: {
  instanceId: string | null;
  open: boolean;
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

  const [instance, setInstance] = useState<ProcessInstanceView | null>(null);
  const [history, setHistory] = useState<HistoryActivityView[]>([]);
  const [bpmnXml, setBpmnXml] = useState('');
  const [layoutJson, setLayoutJson] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [acting, setActing] = useState(false);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<Graph | null>(null);

  // 打开即取详情+历史+定义 XML（三个读面；fail-soft：流程图缺 XML 时空态）
  useEffect(() => {
    if (!open || !instanceId) {
      setInstance(null);
      setHistory([]);
      setBpmnXml('');
      setLayoutJson(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    Promise.all([
      instanceApi.get(instanceId),
      instanceApi.history(instanceId).catch(() => null),
    ])
      .then(([view, historyRows]) => {
        if (cancelled) return;
        setInstance(view);
        setHistory(historyRows ?? []);
      })
      .catch((ex: unknown) => {
        if (!cancelled) message.error(systemErrorMessage(ex, t('workflow.instance.errors.detailFailed')));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, instanceId]);

  // 定义 XML（流程图渲染源；定义详情读面 78，复用 workflowApi.get）
  useEffect(() => {
    const definitionId = instance?.definitionId;
    if (!open || !definitionId) {
      return;
    }
    let cancelled = false;
    workflowApi.get(definitionId)
      .then((view) => {
        if (cancelled) return;
        setBpmnXml(view.bpmnXml ?? '');
        setLayoutJson(view.layoutJson);
      })
      .catch(() => {
        // 流程图缺源=非关键面：drawer 其余四块照常渲染
      });
    return () => {
      cancelled = true;
    };
  }, [open, instance?.definitionId]);

  // readOnly 流程图（bpmnXml/高亮面就绪后装配一次；关闭即销毁）
  const activeNodeIds = useMemo(
    () => new Set((instance?.activeNodes ?? []).map((node) => node.nodeId)),
    [instance],
  );
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
  }, [open, bpmnXml, activeNodeIds, rejectEdgeIds]);

  // 主题变化 → 只读画布网格/背景跟随
  useEffect(() => {
    const graph = graphRef.current;
    if (graph) applyCanvasTheme(graph, canvas);
  }, [canvas]);

  const runAction = async (action: () => Promise<void>, toastKey: string) => {
    setActing(true);
    try {
      await action();
      message.success(t(toastKey));
      onChanged();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('workflow.instance.toast.actionFailed')));
    } finally {
      setActing(false);
    }
  };

  const status = instance?.status;

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={t('workflow.instance.drawer.title')}
      width={880}
      destroyOnHidden
      footer={
        instance ? (
          <Space>
            <Auth code={INSTANCE_MANAGE_PERM}>
              {status === 'RUNNING' ? (
                <>
                  <Popconfirm title={t('workflow.instance.confirm.suspend')} onConfirm={() => void runAction(() => instanceApi.suspend(instance.id, null), 'workflow.instance.toast.suspended')}>
                    <Button disabled={acting}>{t('workflow.instance.actions.suspend')}</Button>
                  </Popconfirm>
                  <Popconfirm title={t('workflow.instance.confirm.terminate')} onConfirm={() => void runAction(() => instanceApi.terminate(instance.id), 'workflow.instance.toast.terminated')}>
                    <Button danger disabled={acting}>{t('workflow.instance.actions.terminate')}</Button>
                  </Popconfirm>
                </>
              ) : null}
              {status === 'SUSPENDED' ? (
                <Popconfirm title={t('workflow.instance.confirm.resume')} onConfirm={() => void runAction(() => instanceApi.resume(instance.id), 'workflow.instance.toast.resumed')}>
                  <Button type="primary" disabled={acting}>{t('workflow.instance.actions.resume')}</Button>
                </Popconfirm>
              ) : null}
            </Auth>
          </Space>
        ) : null
      }
    >
      <Spin spinning={loading}>
        {instance ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
          {/* 一、摘要 */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.instance.drawer.summary')}</Typography.Title>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label={t('workflow.instance.columns.status')}>
                <Tag color={statusBadge(instance.status)} style={{ color: statusColor(instance.status) }}>
                  {t(`workflow.instance.status.${instance.status}`)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('workflow.instance.drawer.initiator')}>{instance.initiator ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.instance.columns.businessKey')}>{instance.businessKey ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.instance.columns.definitionName')}>{instance.definitionName ?? instance.definitionKey ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.instance.drawer.startedAt')}>{formatDate(instance.startTime)}</Descriptions.Item>
              <Descriptions.Item label={t('workflow.instance.drawer.endedAt')}>{instance.endTime ? formatDate(instance.endTime) : '—'}</Descriptions.Item>
              {instance.status === 'SUSPENDED' ? (
                <Descriptions.Item label={t('workflow.instance.suspendReason')} span={2}>{instance.suspendReason ?? '—'}</Descriptions.Item>
              ) : null}
            </Descriptions>
          </section>

          {/* 二、活跃节点 + 任务树 */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.instance.drawer.activeNodes')}</Typography.Title>
            {instance.activeNodes.length === 0 ? (
              <Text type="secondary">{t('workflow.instance.drawer.noActive')}</Text>
            ) : (
              <Space wrap size={8}>
                {instance.activeNodes.map((node) => (
                  <Tag key={node.nodeId} color={TONE_ANTD.info}>
                    {node.nodeName || node.nodeId}
                    {node.enterAt ? ` · ${formatDate(node.enterAt)}` : ''}
                  </Tag>
                ))}
              </Space>
            )}
            <Typography.Title level={5} style={{ marginTop: 16 }}>{t('workflow.instance.drawer.tasks')}</Typography.Title>
            {instance.tasks.length === 0 ? (
              <Text type="secondary">{t('workflow.instance.drawer.noTasks')}</Text>
            ) : (
              <Descriptions size="small" column={1} bordered>
                {instance.tasks.map((task) => (
                  <Descriptions.Item key={task.id} label={task.nodeName || task.nodeId}>
                    <Space size={8} wrap>
                      <Tag color={TONE_ANTD[task.status === 'CLAIMED' ? 'info' : 'muted']}>{t(`workflow.todo.status.${task.status}`)}</Tag>
                      <Text type="secondary">{t('workflow.instance.drawer.assignee')}: {task.assignee ?? '—'}</Text>
                      <Text type="secondary">{t('workflow.instance.drawer.createTime')}: {formatDate(task.createTime)}</Text>
                    </Space>
                  </Descriptions.Item>
                ))}
              </Descriptions>
            )}
          </section>

          {/* 三、变量摘要 */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.instance.drawer.variables')}</Typography.Title>
            {instance.variables && Object.keys(instance.variables).length > 0 ? (
              <Descriptions size="small" column={1} bordered>
                {Object.entries(instance.variables).map(([key, value]) => (
                  <Descriptions.Item key={key} label={key}>
                    <Text code style={{ fontSize: 12 }}>{typeof value === 'string' ? value : JSON.stringify(value)}</Text>
                  </Descriptions.Item>
                ))}
              </Descriptions>
            ) : (
              <Text type="secondary">{t('workflow.instance.drawer.noVariables')}</Text>
            )}
          </section>

          {/* 四、历史时间线（action 分色） */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.instance.drawer.history')}</Typography.Title>
            {history.length === 0 ? (
              <Text type="secondary">{t('workflow.instance.drawer.noHistory')}</Text>
            ) : (
              <Timeline
                items={history.map((item) => ({
                  color: historyActionColor(item.action),
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

          {/* 五、流程图（readOnly 高亮） */}
          <section>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{t('workflow.instance.drawer.diagram')}</Typography.Title>
            {bpmnXml ? (
              <div
                ref={containerRef}
                data-testid="bpmn-readonly-canvas"
                style={{ height: 360, border: '1px solid var(--border)', borderRadius: 8, overflow: 'hidden' }}
              />
            ) : (
              <Text type="secondary">{t('workflow.instance.drawer.diagramFailed')}</Text>
            )}
          </section>
        </div>
        ) : null}
      </Spin>
    </Drawer>
  );
}

/**
 * 实例监控 Tab：CrudTable 分页 + FilterPanel 表单型筛选（查询/重置，JobsPage 口径）
 * + 行操作详情/挂起/终止/恢复（Auth 80 分控）。
 */
export function InstanceMonitorTab(): ReactElement {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [filterForm] = Form.useForm<InstanceFilterFormValues>();

  const crud = useCrud<ProcessInstanceView, InstanceFilters>({
    fetchPage: ({ page, size, filters, signal }) =>
      instanceApi.page(page, size, {
        definitionKey: filters?.definitionKey || undefined,
        status: (filters?.status as ProcessInstanceStatus | undefined) ?? undefined,
        businessKey: filters?.businessKey || undefined,
      }, signal),
    filters: { definitionKey: undefined, status: undefined, businessKey: undefined },
    errorMessage: t('workflow.instance.errors.loadFailed'),
  });

  const [detailId, setDetailId] = useState<string | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const closeDetail = useCallback(() => {
    setDetailOpen(false);
    setDetailId(null);
  }, []);

  const runAction = async (action: () => Promise<void>, toastKey: string) => {
    try {
      await action();
      message.success(t(toastKey));
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('workflow.instance.toast.actionFailed')));
    }
  };

  const columns: ColumnsType<ProcessInstanceView> = [
    { title: t('workflow.instance.columns.id'), dataIndex: 'id', key: 'id', minWidth: 110, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('workflow.instance.columns.definitionKey'), dataIndex: 'definitionKey', key: 'definitionKey', minWidth: 150, render: (v: string | null) => (v ? <Text code>{v}</Text> : '—') },
    { title: t('workflow.instance.columns.definitionName'), dataIndex: 'definitionName', key: 'definitionName', minWidth: 150, ellipsis: true, render: (v: string | null | undefined) => v ?? '—' },
    { title: t('workflow.instance.columns.businessKey'), dataIndex: 'businessKey', key: 'businessKey', minWidth: 170, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('workflow.instance.columns.initiator'), dataIndex: 'initiator', key: 'initiator', minWidth: 110, render: (v: string | null) => v ?? '—' },
    {
      title: t('workflow.instance.columns.status'), dataIndex: 'status', key: 'status', minWidth: 110,
      render: (v: ProcessInstanceStatus) => (
        <Tag color={statusBadge(v)} style={{ color: statusColor(v) }}>{t(`workflow.instance.status.${v}`)}</Tag>
      ),
    },
    { title: t('workflow.instance.columns.startTime'), dataIndex: 'startTime', key: 'startTime', minWidth: 170, render: (v: string) => formatDate(v) },
    { title: t('workflow.instance.columns.endTime'), dataIndex: 'endTime', key: 'endTime', minWidth: 170, render: (v: string | null) => (v ? formatDate(v) : '—') },
    {
      title: t('workflow.instance.columns.actions'), key: 'actions', minWidth: 240, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Button
            size="small"
            onClick={() => {
              setDetailId(record.id);
              setDetailOpen(true);
            }}
          >
            {t('workflow.instance.actions.detail')}
          </Button>
          <Auth code={INSTANCE_MANAGE_PERM}>
            {record.status === 'RUNNING' ? (
              <>
                <Popconfirm title={t('workflow.instance.confirm.suspend')} onConfirm={() => void runAction(() => instanceApi.suspend(record.id, null), 'workflow.instance.toast.suspended')}>
                  <Button size="small">{t('workflow.instance.actions.suspend')}</Button>
                </Popconfirm>
                <Popconfirm title={t('workflow.instance.confirm.terminate')} onConfirm={() => void runAction(() => instanceApi.terminate(record.id), 'workflow.instance.toast.terminated')}>
                  <Button size="small" danger>{t('workflow.instance.actions.terminate')}</Button>
                </Popconfirm>
              </>
            ) : null}
            {record.status === 'SUSPENDED' ? (
              <Popconfirm title={t('workflow.instance.confirm.resume')} onConfirm={() => void runAction(() => instanceApi.resume(record.id), 'workflow.instance.toast.resumed')}>
                <Button size="small">{t('workflow.instance.actions.resume')}</Button>
              </Popconfirm>
            ) : null}
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<ProcessInstanceView, InstanceFilters>
        crud={crud}
        title={t('workflow.instance.title')}
        columns={columns}
        reloadAriaLabel={t('workflow.instance.errors.reloadAria')}
        totalText={(total) => t('common.table.total', { total })}
        filterPanel={
          <Form
            form={filterForm}
            layout="horizontal"
            labelAlign="left"
            colon={false}
            onFinish={(values: InstanceFilterFormValues) => {
              crud.setFilter('definitionKey', values.definitionKey || undefined);
              crud.setFilter('status', values.status || undefined);
              crud.setFilter('businessKey', values.businessKey || undefined);
            }}
          >
            <FilterPanel
              advancedActive={Boolean(crud.filters.definitionKey || crud.filters.status || crud.filters.businessKey)}
              actions={(
                <Space>
                  <Button type="primary" htmlType="submit">{t('common.actions.query')}</Button>
                  <Button onClick={() => { filterForm.resetFields(); crud.resetFilters(); }}>
                    {t('common.actions.reset')}
                  </Button>
                </Space>
              )}
            >
              <Form.Item name="definitionKey" label={t('workflow.instance.filters.definitionKey')}>
                <Input allowClear autoComplete="off" />
              </Form.Item>
              <Form.Item name="businessKey" label={t('workflow.instance.filters.businessKey')}>
                <Input allowClear autoComplete="off" />
              </Form.Item>
              <Form.Item name="status" label={t('workflow.instance.filters.status')}>
                <Select
                  virtual={false}
                  allowClear
                  placeholder={t('common.validation.optional')}
                  options={INSTANCE_STATUSES.map((value) => ({ value, label: t(`workflow.instance.status.${value}`) }))}
                />
              </Form.Item>
            </FilterPanel>
          </Form>
        }
      />
      <InstanceDetailDrawer
        instanceId={detailId}
        open={detailOpen}
        onClose={closeDetail}
        onChanged={() => crud.reload()}
      />
    </>
  );
}
